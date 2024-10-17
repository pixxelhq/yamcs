package org.yamcs.tctm.pus.tuples;

import java.util.Objects;

public class Penta<K, V, L, M, N> {
    private final K first;
    private final V second;
    private final L third;
    private final M fourth;
    private final N fifth;

    public Penta(K first, V second, L third, M fourth, N fifth) {
        this.first = first;
        this.second = second;
        this.third = third;
        this.fourth = fourth;
        this.fifth = fifth;
    }

    public K getFirst() {
        return first;
    }

    public V getSecond() {
        return second;
    }

    public L getThird() {
        return third;
    }

    public M getFourth() {
        return fourth;
    }

    public N getFifth() {
        return fifth;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        Penta<?, ?, ?, ?, ?> penta = (Penta<?, ?, ?, ?, ?>) o;

        return Objects.equals(first, penta.first) && Objects.equals(second, penta.second) && Objects.equals(third, penta.third) && Objects.equals(fourth, penta.fourth) && Objects.equals(fifth, penta.fifth);
    }

    @Override
    public int hashCode() {
        return Objects.hash(first, second, third, fourth, fifth);
    }
}
