package me.lendev.flow.pms.domain.request;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.regex.Pattern;

/**
 * 校验界面主题支持的十六进制和函数式颜色。
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PrimaryColor.Validator.class)
public @interface PrimaryColor {

    String message() default "{error.appearance.primary-color}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<PrimaryColor, String> {

        private static final Pattern HEX = Pattern.compile("#\\p{XDigit}{3,8}");
        private static final Pattern FUNCTION = Pattern.compile("(?:rgb|hsl)a?\\(([^)]*)\\)");
        private static final String NUMBER = "-?\\d+(?:\\.\\d+)?%?";
        private static final Pattern FIRST_COMPONENT = Pattern.compile(NUMBER);
        private static final Pattern NEXT_COMPONENT = Pattern.compile(" *" + NUMBER);

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            if (value.startsWith("#")) {
                return switch (value.length() - 1) {
                    case 3, 4, 6, 8 -> HEX.matcher(value).matches();
                    default -> false;
                };
            }
            var function = FUNCTION.matcher(value);
            if (!function.matches()) {
                return false;
            }
            String[] components = function.group(1).split(",", 5);
            if (components.length < 3 || components.length > 4 || !FIRST_COMPONENT.matcher(components[0]).matches()) {
                return false;
            }
            for (int i = 1; i < components.length; i++) {
                if (!NEXT_COMPONENT.matcher(components[i]).matches()) {
                    return false;
                }
            }
            return true;
        }
    }
}
