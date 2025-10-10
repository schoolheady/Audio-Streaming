package com.example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AppTest {
    @Test
    public void testGreetWithName() {
        assertEquals("Hello, Alice!", App.greet("Alice"));
    }

    @Test
    public void testGreetWithoutName() {
        assertEquals("Hello, World!", App.greet(null));
    }
}
