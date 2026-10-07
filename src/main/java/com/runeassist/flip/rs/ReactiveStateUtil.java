package com.runeassist.flip.rs;

import java.util.function.Function;

public final class ReactiveStateUtil {

    private ReactiveStateUtil() {
    }

    public static <A, B> ReactiveState<B> derive(ReactiveState<A> source, Function<A, B> transform) {
        ReactiveStateImpl<B> derived = new ReactiveStateImpl<>(transform.apply(source.get()));
        source.registerListener(value -> derived.set(transform.apply(value)));
        return derived;
    }

    public static <A, B, C, D> ReactiveState<D> derive(
            ReactiveState<A> first,
            ReactiveState<B> second,
            ReactiveState<C> third,
            TriFunction<A, B, C, D> transform) {
        ReactiveStateImpl<D> derived = new ReactiveStateImpl<>(transform.apply(first.get(), second.get(), third.get()));
        registerJointListener(first, second, third, (value1, value2, value3) ->
                derived.set(transform.apply(value1, value2, value3)));
        return derived;
    }

    public static <A, B, C> void registerJointListener(
            ReactiveState<A> first,
            ReactiveState<B> second,
            ReactiveState<C> third,
            TriConsumer<A, B, C> listener) {
        first.registerListener(value -> listener.accept(value, second.get(), third.get()));
        second.registerListener(value -> listener.accept(first.get(), value, third.get()));
        third.registerListener(value -> listener.accept(first.get(), second.get(), value));
    }

    @FunctionalInterface
    public interface TriConsumer<A, B, C> {
        void accept(A first, B second, C third);
    }

    @FunctionalInterface
    public interface TriFunction<A, B, C, D> {
        D apply(A first, B second, C third);
    }
}
