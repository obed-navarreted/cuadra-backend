package com.cuadra.api.cash;

import com.cuadra.api.common.ReasonBody;
import com.cuadra.api.common.PageResponse;
import com.cuadra.api.security.Actor;
import com.cuadra.api.tenancy.Access;
import com.cuadra.api.tenancy.MemberContext;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/b/{businessId}")
public class CashController {
    private final ExpenseService expenses;
    private final CashMovementService movements;
    private final ShiftService shifts;
    private final Access access;

    public CashController(ExpenseService expenses, CashMovementService movements, ShiftService shifts, Access access) {
        this.expenses = expenses;
        this.movements = movements;
        this.shifts = shifts;
        this.access = access;
    }

    private MemberContext ctx(Actor actor, UUID businessId, UUID memberId) {
        return access.member(actor, businessId, memberId);
    }

    // ---------- gastos ----------

    @GetMapping("/expenses")
    public PageResponse<ExpenseService.ExpenseView> listExpenses(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                                                  @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                                  @RequestParam(required = false) String source, @RequestParam(required = false) UUID categoryId,
                                                                  @RequestParam(defaultValue = "false") boolean includeVoided,
                                                                  @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return expenses.list(ctx(actor, businessId, memberId), from, to, source, categoryId, includeVoided, page, size);
    }

    @GetMapping("/expenses/summary")
    public ExpenseService.Summary expenseSummary(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                                 @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return expenses.summary(ctx(actor, businessId, memberId), from, to);
    }

    @PutMapping("/expenses/{expenseId}")
    public ResponseEntity<ExpenseService.ExpenseView> upsertExpense(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID expenseId,
                                                                     @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId, @RequestBody ExpenseService.ExpenseInput body) {
        ExpenseService.Result r = expenses.upsert(ctx(actor, businessId, memberId), expenseId, body);
        return ResponseEntity.status(r.outcome() == ExpenseService.Outcome.CREATED ? HttpStatus.CREATED : HttpStatus.OK).body(r.expense());
    }

    @PostMapping("/expenses/{expenseId}/void")
    public ExpenseService.ExpenseView voidExpense(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID expenseId,
                                                   @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId, @RequestBody(required = false) ReasonBody body) {
        return expenses.voidExpense(ctx(actor, businessId, memberId), expenseId, body == null ? null : body.reason());
    }

    @GetMapping("/expense-categories")
    public List<ExpenseService.CategoryView> categories(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                                         @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        ctx(actor, businessId, memberId);
        return expenses.categories(businessId);
    }

    @PutMapping("/expense-categories/{categoryId}")
    public ExpenseService.CategoryView upsertCategory(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID categoryId,
                                                       @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId, @RequestBody ExpenseService.CategoryInput body) {
        return expenses.upsertCategory(ctx(actor, businessId, memberId), categoryId, body);
    }

    // ---------- retiros y entradas ----------

    @GetMapping("/cash-movements")
    public PageResponse<CashMovementService.MovementView> listMovements(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                                                         @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                                         @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return movements.list(ctx(actor, businessId, memberId), from, to, page, size);
    }

    @PutMapping("/cash-movements/{movementId}")
    public ResponseEntity<CashMovementService.MovementView> upsertMovement(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID movementId,
                                                                            @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                                            @RequestBody CashMovementService.MovementInput body) {
        CashMovementService.Result r = movements.upsert(ctx(actor, businessId, memberId), movementId, body);
        return ResponseEntity.status(r.outcome() == CashMovementService.Outcome.CREATED ? HttpStatus.CREATED : HttpStatus.OK).body(r.movement());
    }

    @PostMapping("/cash-movements/{movementId}/void")
    public CashMovementService.MovementView voidMovement(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID movementId,
                                                          @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId, @RequestBody(required = false) ReasonBody body) {
        return movements.voidMovement(ctx(actor, businessId, memberId), movementId, body == null ? null : body.reason());
    }

    // ---------- turnos ----------

    @GetMapping("/shifts")
    public PageResponse<ShiftService.ShiftView> listShifts(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                                            @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                            @RequestParam(required = false) UUID registerId, @RequestParam(required = false) String status, @RequestParam(required = false) UUID member,
                                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size) {
        return shifts.list(ctx(actor, businessId, memberId), registerId, status, member, from, to, page, size);
    }

    /** El turno abierto de la caja (o de la del teléfono) con su desglose en vivo; 204 si no hay ninguno. */
    @GetMapping("/shifts/current")
    public ResponseEntity<ShiftService.ShiftView> current(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                                           @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId, @RequestParam(required = false) UUID registerId) {
        return shifts.current(ctx(actor, businessId, memberId), registerId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/shifts/{shiftId}")
    public ShiftService.ShiftView shift(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID shiftId,
                                        @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        return shifts.get(ctx(actor, businessId, memberId), shiftId, true);
    }

    @PutMapping("/shifts/{shiftId}")
    public ResponseEntity<ShiftService.ShiftView> open(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID shiftId,
                                                        @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId, @RequestBody ShiftService.OpenInput body) {
        ShiftService.Result r = shifts.open(ctx(actor, businessId, memberId), shiftId, body);
        return ResponseEntity.status(r.outcome() == ShiftService.Outcome.CREATED ? HttpStatus.CREATED : HttpStatus.OK).body(r.shift());
    }

    @PostMapping("/shifts/{shiftId}/close")
    public ShiftService.ShiftView close(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID shiftId,
                                        @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId, @RequestBody ShiftService.CloseInput body) {
        return shifts.close(ctx(actor, businessId, memberId), shiftId, body).shift();
    }

    @PostMapping("/shifts/{shiftId}/reopen")
    public ShiftService.ShiftView reopen(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID shiftId,
                                         @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId, @RequestBody ReasonBody body) {
        return shifts.reopen(ctx(actor, businessId, memberId), shiftId, body.reason());
    }
}
