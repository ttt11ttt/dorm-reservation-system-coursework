package cn.adc.dental;

public class ApiError extends RuntimeException {
    final int status;
    final String code;
    ApiError(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    static ApiError bad() { return new ApiError(400,"INVALID_INPUT","字段格式或范围无效"); }
    static ApiError denied() { return new ApiError(403,"FORBIDDEN","无权操作该资源"); }
    static ApiError conflict(String code) { return new ApiError(409,code,"状态或请求内容冲突"); }
}
