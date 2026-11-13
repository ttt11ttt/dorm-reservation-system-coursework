package cn.adc.dental;

import java.util.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.MissingServletRequestParameterException;

@RestControllerAdvice
class Errors {
    @ExceptionHandler(ApiError.class)
    ResponseEntity<?> business(ApiError e) { return response(e.status,e.code,e.getMessage()); }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,IllegalArgumentException.class,MissingServletRequestParameterException.class})
    ResponseEntity<?> input(Exception e) { return response(400,"INVALID_INPUT","字段格式或范围无效"); }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<?> missing(Exception e) { return response(404,"NOT_FOUND","资源不存在"); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> integrity(Exception e) { return response(409,"DATA_CONFLICT","数据约束冲突"); }
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<?> lock(Exception e) { return response(409,"RETRY_TRANSACTION","事务冲突，请使用原操作号重试"); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e) { return response(500,"INTERNAL_ERROR","服务处理失败"); }
    private ResponseEntity<?> response(int status,String code,String message) {
        return ResponseEntity.status(status).body(Map.of("code",code,"message",message,"requestId",UUID.randomUUID().toString()));
    }
}
