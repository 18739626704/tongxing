package app.tongxing;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> statusError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message",
                error.getReason() == null ? "请求失败" : error.getReason()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> invalidFields(MethodArgumentNotValidException error) {
        if (error.getBindingResult().getTarget() instanceof SharingController.Registration) {
            Map<String, String> fields = new LinkedHashMap<>();
            for (var field : error.getBindingResult().getFieldErrors()) {
                fields.putIfAbsent(field.getField(), field.getDefaultMessage());
            }
            return ResponseEntity.badRequest().body(Map.of("message", String.join("；", fields.values()), "fields", fields));
        }
        return badInput();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> badInput() {
        return ResponseEntity.badRequest().body(Map.of("message", "请检查输入格式、必填项和长度"));
    }
}
