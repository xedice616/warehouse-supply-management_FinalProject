package com.anbar.wms.common.exception;

import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Turns every exception leaving a controller into an RFC 9457 {@code application/problem+json} response,
 * built by {@link ProblemDetailFactory}. Validation failures add an {@code errors} list of {@link FieldViolation}.
 *
 * <p>Internal details (SQL, class names, stack traces) never reach the client; they are logged instead.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final Comparator<FieldViolation> FIELD_ORDER =
            Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message);

    private final ProblemDetailFactory problems;
    private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

    public GlobalExceptionHandler(ProblemDetailFactory problems) {
        this.problems = problems;
    }

    // ---------------------------------------------------------------- application exceptions

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ProblemDetail> handleBusiness(BusinessException ex) {
        ProblemDetail problem = problems.create(ex.getCode(), ex.getMessage());
        ex.getProperties().forEach(problem::setProperty);
        return respond(problem);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.info("Optimistic lock conflict: {}", ex.getMessage());
        return respond(problems.create(ErrorCode.CONCURRENT_MODIFICATION));
    }

    /** Lock wait timeout or deadlock victim: nothing was written, the client may simply retry. */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleLockFailure(PessimisticLockingFailureException ex) {
        log.warn("Lock conflict: {}", NestedExceptionUtils.getMostSpecificCause(ex).getMessage());
        return respond(problems.create(ErrorCode.CONCURRENT_MODIFICATION,
                "The data is being changed by another request; retry the operation"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex) {
        // Services validate business rules first; reaching this means a database constraint caught something
        // they didn't (often a race). Log the real cause, but don't expose constraint or column names.
        log.warn("Data integrity violation: {}", NestedExceptionUtils.getMostSpecificCause(ex).getMessage());
        return respond(problems.create(ErrorCode.DATA_INTEGRITY_VIOLATION));
    }

    @ExceptionHandler(PropertyReferenceException.class)
    ResponseEntity<ProblemDetail> handleBadSortProperty(PropertyReferenceException ex) {
        ProblemDetail problem = problems.create(ErrorCode.INVALID_SORT_PROPERTY,
                "Unknown property '%s'".formatted(ex.getPropertyName()));
        problem.setProperty("property", ex.getPropertyName());
        return respond(problem);
    }

    /** Bean Validation on service-layer method parameters ({@code @Validated} services). */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(v -> new FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
                .sorted(FIELD_ORDER)
                .toList();
        return respond(validationProblem(violations));
    }

    /**
     * Method security ({@code @PreAuthorize}) denials. Anonymous callers normally never reach a protected controller
     * (the URL rules stop them first), but if they do they get 401, not 403.
     */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean anonymous = authentication == null || trustResolver.isAnonymous(authentication);
        return respond(problems.create(anonymous ? ErrorCode.AUTHENTICATION_REQUIRED : ErrorCode.ACCESS_DENIED));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        return respond(problems.create(ErrorCode.AUTHENTICATION_REQUIRED));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return respond(problems.create(ErrorCode.INTERNAL_ERROR));
    }

    // ---------------------------------------------------------------- framework exceptions (overrides)

    /** {@code @Valid @RequestBody} failures. */
    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(e -> violations.add(new FieldViolation(e.getField(), e.getDefaultMessage())));
        ex.getBindingResult().getGlobalErrors()
                .forEach(e -> violations.add(new FieldViolation(e.getObjectName(), e.getDefaultMessage())));
        violations.sort(FIELD_ORDER);
        return handleExceptionInternal(ex, validationProblem(violations), headers, status, request);
    }

    /** Constraint annotations directly on controller method parameters ({@code @RequestParam @Min(1)}, ...). */
    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors()
                    .forEach(e -> violations.add(new FieldViolation(name, e.getDefaultMessage())));
        }
        violations.sort(FIELD_ORDER);
        return handleExceptionInternal(ex, validationProblem(violations), headers, status, request);
    }

    /** Unparseable JSON, wrong types, and properties the DTO doesn't declare (e.g. {@code quantity} on a product). */
    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problems.create(ErrorCode.MALFORMED_REQUEST, describeUnreadable(ex));
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /**
     * Common exit for every framework-handled exception: makes sure even the problems built by Spring
     * (404 no handler, 405, 415, type mismatch, ...) carry {@code code}, {@code timestamp} and {@code correlationId}.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        // Spring passes body == null for most framework exceptions and builds the ProblemDetail in super,
        // so enrich the final body rather than the argument.
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            problems.enrich(problem, ErrorCode.fromStatus(response.getStatusCode().value()));
        }
        return response;
    }

    // ---------------------------------------------------------------- helpers

    private ProblemDetail validationProblem(List<FieldViolation> violations) {
        ProblemDetail problem = problems.create(ErrorCode.VALIDATION_FAILED);
        problem.setProperty(ProblemDetailFactory.ERRORS, violations);
        return problem;
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    private static String describeUnreadable(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getMostSpecificCause();
        if (cause instanceof UnrecognizedPropertyException unknown) {
            return "Unknown property '%s'".formatted(jsonPath(unknown));
        }
        if (cause instanceof JacksonException jackson && !jackson.getPath().isEmpty()) {
            return "Invalid value for '%s'".formatted(jsonPath(jackson));
        }
        return "Request body is missing or is not valid JSON";
    }

    /** {@code lines[2].quantity} style path to the offending JSON element. */
    private static String jsonPath(JacksonException ex) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference ref : ex.getPath()) {
            if (ref.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(ref.getPropertyName());
            } else if (ref.getIndex() >= 0) {
                path.append('[').append(ref.getIndex()).append(']');
            }
        }
        return path.toString();
    }
}
