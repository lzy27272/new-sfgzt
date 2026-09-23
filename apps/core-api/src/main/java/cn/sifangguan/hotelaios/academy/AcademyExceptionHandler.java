package cn.sifangguan.hotelaios.academy;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Preserve actionable course, publishing and learning validation messages in the API response. */
@RestControllerAdvice(assignableTypes = AcademyController.class)
public class AcademyExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail academyProblem(ResponseStatusException exception) {
        var problem = ProblemDetail.forStatusAndDetail(exception.getStatusCode(), exception.getReason());
        problem.setTitle("学院操作未完成");
        problem.setProperty("code", "ACADEMY_REQUEST_INVALID");
        return problem;
    }
}
