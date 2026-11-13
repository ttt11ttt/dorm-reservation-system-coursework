package cn.adc.dental;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.*;
import tools.jackson.databind.cfg.*;

@Configuration(proxyBeanMethods=false)
class JsonConfiguration {
    @Bean JsonMapperBuilderCustomizer strictJson() {
        return builder->builder
            .withCoercionConfig(String.class,c->c.setCoercion(CoercionInputShape.Integer,CoercionAction.Fail).setCoercion(CoercionInputShape.Float,CoercionAction.Fail).setCoercion(CoercionInputShape.Boolean,CoercionAction.Fail))
            .withCoercionConfig(Integer.class,c->c.setCoercion(CoercionInputShape.Float,CoercionAction.Fail).setCoercion(CoercionInputShape.String,CoercionAction.Fail).setCoercion(CoercionInputShape.Boolean,CoercionAction.Fail));
    }
}
