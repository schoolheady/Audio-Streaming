package com.example;

/**
 * Simple App class to verify project skeleton.
 */
public class App {
    public static String greet(String name) {
        if (name == null || name.isBlank()) return "Hello, World!";
        return "Hello, " + name + "!";
    }

    public static void main(String[] args) {
        System.out.println(greet(" "));
    }
}
