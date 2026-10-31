package com.quant.risk;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 每個 @Service/@Component 必須只有一個建構子，或用 @Autowired 標明要用哪個；
 * 否則 Spring 開機丟 "No default constructor found"（CI 只跑單元測試抓不到，本機 docker 起來才炸）。
 */
class BeanConstructorTest {
    @Test
    void everyBeanHasAnUnambiguousConstructor() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        List<String> bad = new ArrayList<>();
        for (var bd : scanner.findCandidateComponents("com.quant.risk")) {
            try {
                Class<?> c = Class.forName(bd.getBeanClassName());
                Constructor<?>[] ctors = c.getDeclaredConstructors();
                boolean ok = ctors.length == 1 || java.util.Arrays.stream(ctors).anyMatch(k -> k.isAnnotationPresent(Autowired.class));
                if (!ok) bad.add(c.getSimpleName() + " 有 " + ctors.length + " 個建構子且無 @Autowired");
            } catch (ClassNotFoundException ignore) {}
        }
        assertTrue(bad.isEmpty(), String.join("; ", bad));
    }
}
