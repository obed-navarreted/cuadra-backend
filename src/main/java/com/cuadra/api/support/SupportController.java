package com.cuadra.api.support;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.config.CuadraProperties;
import com.cuadra.api.security.Actor;
import com.cuadra.api.tenancy.Access;
import com.cuadra.api.tenancy.MemberContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/support")
public class SupportController {
    private static final Logger log = LoggerFactory.getLogger(SupportController.class);
    private static final int MAX_PER_HOUR = 5;

    private final JdbcClient jdbc;
    private final MailSender mail;
    private final CuadraProperties props;
    private final Access access;
    private final Clock clock;

    public SupportController(JdbcClient jdbc, MailSender mail, CuadraProperties props, Access access, Clock clock) {
        this.jdbc = jdbc;
        this.mail = mail;
        this.props = props;
        this.access = access;
        this.clock = clock;
    }

    public record TicketRequest(@Pattern(regexp = "QUESTION|PROBLEM|SUGGESTION|BILLING") String category,
                                @NotBlank @Size(min = 10, max = 4000) String message,
                                @Size(max = 200) String replyToEmail, @Size(max = 40) String replyToPhone,
                                @Size(max = 20000) String diagnostics, @Pattern(regexp = "es|en") String locale,
                                UUID businessId) {}

    public record TicketCreated(UUID id, String reference) {}

    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketCreated create(@AuthenticationPrincipal Actor actor,
                                @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberHeader,
                                @Valid @RequestBody TicketRequest body) {
        UUID userId = actor.userId();
        UUID businessId = actor.isDevice() ? actor.deviceBusinessId() : body.businessId();
        UUID memberId = null;
        if (businessId != null) {
            // Con teléfono se identifica a la persona por X-Member-Id; con Google, por su membresía.
            MemberContext ctx = actor.isDevice() && memberHeader == null ? null : access.member(actor, businessId, memberHeader);
            if (ctx != null) memberId = ctx.memberId();
        }

        String replyEmail = body.replyToEmail();
        if ((replyEmail == null || replyEmail.isBlank()) && userId != null) {
            replyEmail = jdbc.sql("SELECT email FROM user_account WHERE id = :u").param("u", userId).query(String.class).single();
        }

        // Límite por persona (o por negocio si el teléfono no identificó a nadie): 5 tickets por hora.
        Timestamp since = Timestamp.from(clock.instant().minus(Duration.ofHours(1)));
        int recent = jdbc.sql("""
                        SELECT count(*) FROM support_ticket WHERE created_at > :since AND
                               ((:u IS NOT NULL AND user_account_id = :u) OR (:m IS NOT NULL AND member_id = :m)
                                OR (:u IS NULL AND :m IS NULL AND business_id = :b))
                        """)
                .param("since", since).param("u", userId, java.sql.Types.OTHER).param("m", memberId, java.sql.Types.OTHER)
                .param("b", businessId, java.sql.Types.OTHER).query(Integer.class).single();
        if (recent >= MAX_PER_HOUR) throw ApiException.tooMany("TOO_MANY_TICKETS", "Too many tickets, try again later");

        UUID id = UUID.randomUUID();
        String category = body.category() == null ? "QUESTION" : body.category();
        jdbc.sql("""
                        INSERT INTO support_ticket (id, user_account_id, member_id, business_id, category, message, reply_to_email,
                                                    reply_to_phone, diagnostics, locale)
                        VALUES (:id, :u, :m, :b, :c, :msg, :re, :rp, :d, :l)
                        """)
                .param("id", id).param("u", userId, java.sql.Types.OTHER).param("m", memberId, java.sql.Types.OTHER)
                .param("b", businessId, java.sql.Types.OTHER).param("c", category).param("msg", body.message().trim())
                .param("re", replyEmail).param("rp", body.replyToPhone()).param("d", body.diagnostics())
                .param("l", body.locale() == null ? "es" : body.locale()).update();

        String reference = "#" + id.toString().substring(0, 8);
        String businessName = businessId == null ? "-" : jdbc.sql("SELECT name FROM business WHERE id = :b").param("b", businessId)
                .query(String.class).optional().orElse("-");
        String subject = "[Cuadra " + reference + "] " + category + " — " + businessName;
        String text = body.message().trim() + "\n\n--\nResponder a: " + (replyEmail == null ? "-" : replyEmail)
                + (body.replyToPhone() == null ? "" : " / " + body.replyToPhone())
                + "\nNegocio: " + businessName + " (" + businessId + ")\nMiembro: " + memberId
                + (body.diagnostics() == null ? "" : "\n\nDatos técnicos:\n" + body.diagnostics());
        try {
            mail.send(props.support().inboxEmail(), replyEmail, subject, text);
            jdbc.sql("UPDATE support_ticket SET emailed_at = :now WHERE id = :id")
                    .param("now", Timestamp.from(clock.instant())).param("id", id).update();
        } catch (RuntimeException e) {
            // El ticket ya está guardado y visible en la consola; el correo no bloquea la respuesta.
            log.warn("No se pudo enviar el correo del ticket {}: {}", reference, e.getMessage());
        }
        return new TicketCreated(id, reference);
    }
}
