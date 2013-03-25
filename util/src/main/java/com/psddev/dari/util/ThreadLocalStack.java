package com.psddev.dari.util;

import java.util.ArrayDeque;
import java.util.Deque;

public class ThreadLocalStack<T> {

    private final ThreadLocal<Deque<T>> stackLocal = new ThreadLocal<Deque<T>>();

    public void push(T object) {
        Deque<T> stack = stackLocal.get();

        if (stack == null) {
            stack = new ArrayDeque<T>();
            stackLocal.set(stack);
        }

        stack.addFirst(object);
    }

    public T get() {
        Deque<T> stack = stackLocal.get();

        return stack != null ? stack.peekFirst() : null;
    }

    public T pop() {
        Deque<T> stack = stackLocal.get();

        if (stack == null || stack.isEmpty()) {
            return null;

        } else {
            T popped = stack.pollFirst();
            stackLocal.remove();
            return popped;
        }
    }

    public void set(T value) {
        Deque<T> stack = new ArrayDeque<T>();

        stack.addFirst(value);
        stackLocal.set(stack);
    }

    public void remove() {
        stackLocal.remove();
    }
}
