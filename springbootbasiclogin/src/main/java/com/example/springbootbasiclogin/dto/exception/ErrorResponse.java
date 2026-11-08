package com.example.springbootbasiclogin.dto.exception;

import com.example.springbootbasiclogin.dto.BaseResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;

@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@SuppressWarnings("unchecked")
public class ErrorResponse extends BaseResponse<Object> {

    @Data
    @Builder
    public static class ErrorDetailsBody {
        private StackTraceElement[] stackTraces;
        @Schema(description = "Details of what field has error and what is the error")
        private List<FieldErrorDetail> invalidFields;
    }
}
