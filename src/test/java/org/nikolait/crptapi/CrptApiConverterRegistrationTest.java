package org.nikolait.crptapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CrptApiConverterRegistrationTest {

    @Test
    @DisplayName("Регистрация и перезапись конвертера до заморозки работает корректно")
    void register_overwriteBeforeFreeze_ok() {
        CrptApi api = new CrptApi(TimeUnit.SECONDS, 1);
        CrptApi.DocumentConverter c1 = d -> "one".getBytes();
        CrptApi.DocumentConverter c2 = d -> "two".getBytes();

        api.registerConverter("manual", c1);
        assertSame(c1, api.converters.get("MANUAL"));

        api.registerConverter("MANUAL", c2);
        assertSame(c2, api.converters.get("MANUAL"));
    }

    @Test
    @DisplayName("Регистрация после freezeConvertersIfNeeded() вызывает IllegalStateException")
    void register_afterFreeze_illegalState() {
        CrptApi api = new CrptApi(TimeUnit.SECONDS, 1);
        api.freezeConvertersIfNeeded();

        assertThrows(IllegalStateException.class,
                () -> api.registerConverter("XML", d -> new byte[0]));
    }

    @Test
    @DisplayName("Проверка валидации аргументов registerConverter()")
    void register_invalidArgs_throws() {
        CrptApi api = new CrptApi(TimeUnit.SECONDS, 1);

        assertThrows(IllegalArgumentException.class,
                () -> api.registerConverter(" ", d -> new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> api.registerConverter(null, d -> new byte[0]));
        assertThrows(NullPointerException.class,
                () -> api.registerConverter("MANUAL", null));
    }
}
