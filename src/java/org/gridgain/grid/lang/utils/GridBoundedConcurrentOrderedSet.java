// Copyright (C) GridGain Systems Licensed under GPLv3, http://www.gnu.org/licenses/gpl.html

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.lang.utils;

import org.gridgain.grid.util.*;

import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * Concurrent ordered set that automatically manages its maximum size.
 * Once it exceeds its maximum, it will start removing smallest elements
 * until the maximum is reached again.
 * <p>
 * Note that due to concurrent nature of this set, it may grow slightly
 * larger than its maximum allowed size, but in this case it will quickly
 * readjust back to allowed size.
 * <p>
 * Note that {@link #remove(Object)} method is not supported for this kind of set.
 *
 * @author 2012 Copyright (C) GridGain Systems
 * @version 4.0.3c.14052012
 */
public class GridBoundedConcurrentOrderedSet<E> extends GridConcurrentSkipListSet<E> {
    /** Element count. */
    private final AtomicInteger cnt = new AtomicInteger(0);

    /** Maximum size. */
    private int max;

    /**
     * Constructs a new, empty set that orders its elements according to
     * their {@linkplain Comparable natural ordering}.
     * 
     * @param max Upper bound of this set.
     */
    public GridBoundedConcurrentOrderedSet(int max) {
        assert max > 0;

        this.max = max;
    }

    /**
     * Constructs a new, empty set that orders its elements according to
     * the specified comparator.
     *
     * @param max Upper bound of this set.
     * @param comparator the comparator that will be used to order this set.
     *      If <tt>null</tt>, the {@linkplain Comparable natural
     *      ordering} of the elements will be used.
     */
    public GridBoundedConcurrentOrderedSet(int max, Comparator<? super E> comparator) {
        super(comparator);

        assert max > 0;

        this.max = max;
    }

    /**
     * Constructs a new set containing the elements in the specified
     * collection, that orders its elements according to their
     * {@linkplain Comparable natural ordering}.
     *
     * @param max Upper bound of this set.
     * @param c The elements that will comprise the new set
     * @throws ClassCastException if the elements in <tt>c</tt> are
     *      not {@link Comparable}, or are not mutually comparable
     * @throws NullPointerException if the specified collection or any
     *      of its elements are {@code null}.
     */
    public GridBoundedConcurrentOrderedSet(int max, Collection<? extends E> c) {
        super(c);

        assert max > 0;

        this.max = max;
    }

    /**
     * Constructs a new set containing the same elements and using the
     * same ordering as the specified sorted set.
     *
     * @param max Upper bound of this set.
     * @param s sorted set whose elements will comprise the new set
     * @throws NullPointerException if the specified sorted set or any
     *      of its elements are {@code null}.
     */
    public GridBoundedConcurrentOrderedSet(int max, SortedSet<E> s) {
        super(s);

        assert max > 0;

        this.max = max;
    }

    /** {@inheritDoc} */
    @Override public boolean add(E e) {
        GridArgumentCheck.notNull(e, "e");
        
        if (super.add(e)) {
            cnt.incrementAndGet();

            int c;

            while ((c = cnt.get()) > max) {
                // Decrement count.
                if (cnt.compareAndSet(c, c - 1)) {
                    try {
                        while (!super.remove(first())) {
                            // No-op.
                        }
                    }
                    catch (NoSuchElementException e1) {
                        e1.printStackTrace(); // Should never happen.

                        assert false : "Internal error in grid bounded ordered set.";
                    }
                }
            }

            return true;
        }

        return false;
    }

    /**
     * Approximate size at this point of time. Note, that unlike {@code size}
     * methods on other {@code concurrent} collections, this method executes
     * in constant time without traversal of the elements.
     *
     * @return Approximate set size at this point of time.
     */
    @Override public int size() {
        return cnt.get();
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked"})
    @Override public GridBoundedConcurrentOrderedSet<E> clone() {
        GridBoundedConcurrentOrderedSet<E> s = (GridBoundedConcurrentOrderedSet<E>)super.clone();

        s.max = max;

        return s;
    }

    /**
     * This method is not supported and always throws {@link UnsupportedOperationException}.
     *
     * @param o {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override public boolean remove(Object o) {
        throw new UnsupportedOperationException("Remove is not supported on concurrent bounded set.");
    }
}
