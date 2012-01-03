// Copyright (C) GridGain Systems Licensed under GPLv3, http://www.gnu.org/licenses/gpl.html

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.lang.utils;

import org.gridgain.grid.lang.*;
import org.gridgain.grid.typedef.*;
import org.gridgain.grid.util.*;
import org.jetbrains.annotations.*;
import sun.misc.*;

import java.util.*;
import java.util.Queue;
import java.util.concurrent.atomic.*;

/**
 * An unbounded concurrent {@linkplain Deque deque} based on linked nodes.
 * Concurrent insertion, removal, and access operations execute safely
 * across multiple threads.
 * A {@code ConcurrentLinkedDeque} is an appropriate choice when
 * many threads will share access to a common collection.
 * Like most other concurrent collection implementations, this class
 * does not permit the use of {@code null} elements.
 *
 * <p>Iterators are <i>weakly consistent</i>, returning elements
 * reflecting the state of the deque at some point at or since the
 * creation of the iterator.  They do <em>not</em> throw {@link
 * java.util.ConcurrentModificationException
 * ConcurrentModificationException}, and may proceed concurrently with
 * other operations.
 *
 * <p>Beware that, unlike in most collections, the {@code size} method
 * is <em>NOT</em> a constant-time operation. Because of the
 * asynchronous nature of these deques, determining the current number
 * of elements requires a traversal of the elements, and so may report
 * inaccurate results if this collection is modified during traversal.
 * Additionally, the bulk operations {@code addAll},
 * {@code removeAll}, {@code retainAll}, {@code containsAll},
 * {@code equals}, and {@code toArray} are <em>not</em> guaranteed
 * to be performed atomically. For example, an iterator operating
 * concurrently with an {@code addAll} operation might view only some
 * of the added elements.
 *
 * <p>This class and its iterator implement all of the <em>optional</em>
 * methods of the {@link Deque} and {@link Iterator} interfaces.
 *
 * <p>Memory consistency effects: As with other concurrent collections,
 * actions in a thread prior to placing an object into a
 * {@code ConcurrentLinkedDeque}
 * <a href="package-summary.html#MemoryVisibility"><i>happen-before</i></a>
 * actions subsequent to the access or removal of that element from
 * the {@code ConcurrentLinkedDeque} in another thread.
 * <p>
 * Written by Doug Lea and Martin Buchholz with assistance from members of
 * JCP JSR-166 Expert Group and released to the public domain, as explained
 * at http://creativecommons.org/publicdomain/zero/1.0/
 *
 * @author 2012 Copyright (C) GridGain Systems
 * @version 3.6.0c.03012012
 */
@SuppressWarnings( {"ALL"})
public class GridConcurrentLinkedDeque<E> extends AbstractCollection<E> implements Deque<E> {
    /*
     * This is an implementation of a concurrent lock-free deque
     * supporting interior removes but not interior insertions, as
     * required to support the entire Deque interface.
     *
     * We extend the techniques developed for ConcurrentLinkedQueue and
     * LinkedTransferQueue (see the internal docs for those classes).
     * Understanding the ConcurrentLinkedQueue implementation is a
     * prerequisite for understanding the implementation of this class.
     *
     * The data structure is a symmetrical doubly-linked "GC-robust"
     * linked list of nodes.  We minimize the number of volatile writes
     * using two techniques: advancing multiple hops with a single CAS
     * and mixing volatile and non-volatile writes of the same memory
     * locations.
     *
     * A node contains the expected E ("item") and links to predecessor
     * ("prev") and successor ("next") nodes:
     *
     * class Node<E> { volatile Node<E> prev, next; volatile E item; }
     *
     * A node p is considered "live" if it contains a non-null item
     * (p.item != null).  When an item is CASed to null, the item is
     * atomically logically deleted from the collection.
     *
     * At any time, there is precisely one "first" node with a null
     * prev reference that terminates any chain of prev references
     * starting at a live node.  Similarly there is precisely one
     * "last" node terminating any chain of next references starting at
     * a live node.  The "first" and "last" nodes may or may not be live.
     * The "first" and "last" nodes are always mutually reachable.
     *
     * A new element is added atomically by CASing the null prev or
     * next reference in the first or last node to a fresh node
     * containing the element.  The element's node atomically becomes
     * "live" at that point.
     *
     * A node is considered "active" if it is a live node, or the
     * first or last node.  Active nodes cannot be unlinked.
     *
     * A "self-link" is a next or prev reference that is the same node:
     *   p.prev == p  or  p.next == p
     * Self-links are used in the node unlinking process.  Active nodes
     * never have self-links.
     *
     * A node p is active if and only if:
     *
     * p.item != null ||
     * (p.prev == null && p.next != p) ||
     * (p.next == null && p.prev != p)
     *
     * The deque object has two node references, "head" and "tail".
     * The head and tail are only approximations to the first and last
     * nodes of the deque.  The first node can always be found by
     * following prev pointers from head; likewise for tail.  However,
     * it is permissible for head and tail to be referring to deleted
     * nodes that have been unlinked and so may not be reachable from
     * any live node.
     *
     * There are 3 stages of node deletion;
     * "logical deletion", "unlinking", and "gc-unlinking".
     *
     * 1. "logical deletion" by CASing item to null atomically removes
     * the element from the collection, and makes the containing node
     * eligible for unlinking.
     *
     * 2. "unlinking" makes a deleted node unreachable from active
     * nodes, and thus eventually reclaimable by GC.  Unlinked nodes
     * may remain reachable indefinitely from an iterator.
     *
     * Physical node unlinking is merely an optimization (albeit a
     * critical one), and so can be performed at our convenience.  At
     * any time, the set of live nodes maintained by prev and next
     * links are identical, that is, the live nodes found via next
     * links from the first node is equal to the elements found via
     * prev links from the last node.  However, this is not true for
     * nodes that have already been logically deleted - such nodes may
     * be reachable in one direction only.
     *
     * 3. "gc-unlinking" takes unlinking further by making active
     * nodes unreachable from deleted nodes, making it easier for the
     * GC to reclaim future deleted nodes.  This step makes the data
     * structure "gc-robust", as first described in detail by Boehm
     * (http://portal.acm.org/citation.cfm?doid=503272.503282).
     *
     * GC-unlinked nodes may remain reachable indefinitely from an
     * iterator, but unlike unlinked nodes, are never reachable from
     * head or tail.
     *
     * Making the data structure GC-robust will eliminate the risk of
     * unbounded memory retention with conservative GCs and is likely
     * to improve performance with generational GCs.
     *
     * When a node is dequeued at either end, e.g. via poll(), we would
     * like to break any references from the node to active nodes.  We
     * develop further the use of self-links that was very effective in
     * other concurrent collection classes.  The idea is to replace
     * prev and next pointers with special values that are interpreted
     * to mean off-the-list-at-one-end.  These are approximations, but
     * good enough to preserve the properties we want in our
     * traversals, e.g. we guarantee that a traversal will never visit
     * the same element twice, but we don't guarantee whether a
     * traversal that runs out of elements will be able to see more
     * elements later after enqueues at that end.  Doing gc-unlinking
     * safely is particularly tricky, since any node can be in use
     * indefinitely (for example by an iterator).  We must ensure that
     * the nodes pointed at by head/tail never get gc-unlinked, since
     * head/tail are needed to get "back on track" by other nodes that
     * are gc-unlinked.  gc-unlinking accounts for much of the
     * implementation complexity.
     *
     * Since neither unlinking nor gc-unlinking are necessary for
     * correctness, there are many implementation choices regarding
     * frequency (eagerness) of these operations.  Since volatile
     * reads are likely to be much cheaper than CASes, saving CASes by
     * unlinking multiple adjacent nodes at a time may be a win.
     * gc-unlinking can be performed rarely and still be effective,
     * since it is most important that long chains of deleted nodes
     * are occasionally broken.
     *
     * The actual representation we use is that p.next == p means to
     * goto the first node (which in turn is reached by following prev
     * pointers from head), and p.next == null && p.prev == p means
     * that the iteration is at an end and that p is a (static final)
     * dummy node, NEXT_TERMINATOR, and not the last active node.
     * Finishing the iteration when encountering such a TERMINATOR is
     * good enough for read-only traversals, so such traversals can use
     * p.next == null as the termination condition.  When we need to
     * find the last (active) node, for enqueueing a new node, we need
     * to check whether we have reached a TERMINATOR node; if so,
     * restart traversal from tail.
     *
     * The implementation is completely directionally symmetrical,
     * except that most public methods that iterate through the list
     * follow next pointers ("forward" direction).
     *
     * We believe (without full proof) that all single-element deque
     * operations (e.g., addFirst, peekLast, pollLast) are linearizable
     * (see Herlihy and Shavit's book).  However, some combinations of
     * operations are known not to be linearizable.  In particular,
     * when an addFirst(A) is racing with pollFirst() removing B, it is
     * possible for an observer iterating over the elements to observe
     * A B C and subsequently observe A C, even though no interior
     * removes are ever performed.  Nevertheless, iterators behave
     * reasonably, providing the "weakly consistent" guarantees.
     *
     * Empirically, microbenchmarks suggest that this class adds about
     * 40% overhead relative to ConcurrentLinkedQueue, which feels as
     * good as we can hope for.
     */

    /**
     * A node from which the first node on list (that is, the unique node p
     * with p.prev == null && p.next != p) can be reached in O(1) time.
     * Invariants:
     * - the first node is always O(1) reachable from head via prev links
     * - all live nodes are reachable from the first node via succ()
     * - head != null
     * - (tmp = head).next != tmp || tmp != head
     * - head is never gc-unlinked (but may be unlinked)
     * Non-invariants:
     * - head.item may or may not be null
     * - head may not be reachable from the first or last node, or from tail
     */
    private volatile Node<E> head;

    /**
     * A node from which the last node on list (that is, the unique node p
     * with p.next == null && p.prev != p) can be reached in O(1) time.
     * Invariants:
     * - the last node is always O(1) reachable from tail via next links
     * - all live nodes are reachable from the last node via pred()
     * - tail != null
     * - tail is never gc-unlinked (but may be unlinked)
     * Non-invariants:
     * - tail.item may or may not be null
     * - tail may not be reachable from the first or last node, or from head
     */
    private volatile Node<E> tail;

    /** */
    private final AtomicInteger size = new AtomicInteger();

    /** Previous and next terminators. */
    private static final Node<Object> PREV_TERMINATOR, NEXT_TERMINATOR;

    @SuppressWarnings("unchecked")
    Node<E> prevTerminator() {
        return (Node<E>) PREV_TERMINATOR;
    }

    @SuppressWarnings("unchecked")
    Node<E> nextTerminator() {
        return (Node<E>) NEXT_TERMINATOR;
    }

    /**
     * Internal node element.
     *
     * @param <E> Node item.
     */
    @SuppressWarnings( {"PackageVisibleField", "PackageVisibleInnerClass"})
    public static final class Node<E> {
        volatile Node<E> prev;
        volatile E item;
        volatile Node<E> next;

        /**
         * Default constructor for NEXT_TERMINATOR, PREV_TERMINATOR.
         */
        Node() {
            // No-op.
        }

        /**
         * Constructs a new node.  Uses relaxed write because item can
         * only be seen after publication via casNext or casPrev.
         *
         * @param item Item to initialize.
         */
        public Node(E item) {
            UNSAFE.putObject(this, itemOffset, item);
        }

        /**
         * @return Item.
         */
        @Nullable public E item() {
            return item;
        }

        /**
         * @param cmp Compare value.
         * @param val New value.
         * @return {@code True} if set.
         */
        boolean casItem(E cmp, @Nullable E val) {
            return UNSAFE.compareAndSwapObject(this, itemOffset, cmp, val);
        }

        /**
         * @param val New value.
         */
        void lazySetNext(Node<E> val) {
            UNSAFE.putOrderedObject(this, nextOffset, val);
        }

        /**
         * @param cmp Compare value.
         * @param val New value.
         * @return {@code True} if set.
         */
        boolean casNext(@Nullable Node<E> cmp, Node<E> val) {
            return UNSAFE.compareAndSwapObject(this, nextOffset, cmp, val);
        }

        /**
         * @param val New value.
         */
        void lazySetPrev(Node<E> val) {
            UNSAFE.putOrderedObject(this, prevOffset, val);
        }

        /**
         * @param cmp Compare value.
         * @param val New value.
         * @return {@code True} if set.
         */
        boolean casPrev(Node<E> cmp, Node<E> val) {
            return UNSAFE.compareAndSwapObject(this, prevOffset, cmp, val);
        }

        /** Unsafe. */
        private static final Unsafe UNSAFE;

        /** Previous field offset. */
        private static final long prevOffset;

        /** Item field offset. */
        private static final long itemOffset;

        /** Next field offset. */
        private static final long nextOffset;

        /**
         * Initialize offsets.
         */
        static {
            try {
                UNSAFE = GridUnsafe.unsafe();

                Class k = Node.class;

                prevOffset = UNSAFE.objectFieldOffset(k.getDeclaredField("prev"));
                itemOffset = UNSAFE.objectFieldOffset(k.getDeclaredField("item"));
                nextOffset = UNSAFE.objectFieldOffset(k.getDeclaredField("next"));
            }
            catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    /**
     * Links e as first element.
     */
    private void linkFirst(E e) {
        checkNotNull(e);

        size.incrementAndGet();

        final Node<E> newNode = new Node<E>(e);

        restartFromHead:
        for (;;) {
            for (Node<E> h = head, p = h, q;;) {
                if ((q = p.prev) != null && (q = (p = q).prev) != null)
                    // Check for head updates every other hop.
                    // If p == q, we are sure to follow head instead.
                    p = (h != (h = head)) ? h : q;
                else if (p.next == p) // PREV_TERMINATOR
                    continue restartFromHead;
                else {
                    // p is first node
                    newNode.lazySetNext(p); // CAS piggyback.

                    if (p.casPrev(null, newNode)) {
                        // Successful CAS is the linearization point
                        // for e to become an element of this deque,
                        // and for newNode to become "live".
                        if (p != h) // hop two nodes at a time
                            casHead(h, newNode);  // Failure is OK.

                        return;
                    }
                    // Lost CAS race to another thread; re-read prev
                }
            }
        }
    }

    /**
     * Same as {@link #linkFirst(Object)}, but returns new {@link Node}.
     *
     * @param e Element to link.
     * @return New node.
     */
    private Node<E> linkFirstx(E e) {
        checkNotNull(e);

        size.incrementAndGet();

        final Node<E> newNode = new Node<E>(e);

        restartFromHead:
        for (;;) {
            for (Node<E> h = head, p = h, q;;) {
                if ((q = p.prev) != null && (q = (p = q).prev) != null)
                    // Check for head updates every other hop.
                    // If p == q, we are sure to follow head instead.
                    p = (h != (h = head)) ? h : q;
                else if (p.next == p) // PREV_TERMINATOR
                    continue restartFromHead;
                else {
                    // p is first node
                    newNode.lazySetNext(p); // CAS piggyback.

                    if (p.casPrev(null, newNode)) {
                        // Successful CAS is the linearization point
                        // for e to become an element of this deque,
                        // and for newNode to become "live".
                        if (p != h) // hop two nodes at a time
                            casHead(h, newNode);  // Failure is OK.

                        return newNode;
                    }
                    // Lost CAS race to another thread; re-read prev
                }
            }
        }
    }

    /**
     * Links e as last element.
     *
     * @param e Element to link.
     */
    private void linkLast(E e) {
        checkNotNull(e);

        size.incrementAndGet();

        final Node<E> newNode = new Node<E>(e);

        restartFromTail:
        for (;;) {
            for (Node<E> t = tail, p = t, q;;) {
                if ((q = p.next) != null && (q = (p = q).next) != null)
                    // Check for tail updates every other hop.
                    // If p == q, we are sure to follow tail instead.
                    p = (t != (t = tail)) ? t : q;
                else if (p.prev == p) // NEXT_TERMINATOR
                    continue restartFromTail;
                else {
                    // p is last node
                    newNode.lazySetPrev(p); // CAS piggyback.

                    if (p.casNext(null, newNode)) {
                        // Successful CAS is the linearization point
                        // for e to become an element of this deque,
                        // and for newNode to become "live".
                        if (p != t) // hop two nodes at a time
                            casTail(t, newNode);  // Failure is OK.

                        return;
                    }
                    // Lost CAS race to another thread; re-read next
                }
            }
        }
    }

    /**
     * Links n as last node.
     *
     * @param n Node to link.
     */
    private void linkLast(Node<E> n) {
        checkNotNull(n);

        size.incrementAndGet();

        restartFromTail:
        for (;;) {
            for (Node<E> t = tail, p = t, q;;) {
                if ((q = p.next) != null && (q = (p = q).next) != null)
                    // Check for tail updates every other hop.
                    // If p == q, we are sure to follow tail instead.
                    p = (t != (t = tail)) ? t : q;
                else if (p.prev == p) // NEXT_TERMINATOR
                    continue restartFromTail;
                else {
                    // p is last node
                    n.lazySetPrev(p); // CAS piggyback.

                    if (p.casNext(null, n)) {
                        // Successful CAS is the linearization point
                        // for e to become an element of this deque,
                        // and for newNode to become "live".
                        if (p != t) // hop two nodes at a time
                            casTail(t, n);  // Failure is OK.

                        return;
                    }
                    // Lost CAS race to another thread; re-read next
                }
            }
        }
    }

    /**
     * Same as {@link #linkLast(Object)}, but returns {@link Node}.
     *
     * @param e Element to link.
     * @return New node.
     */
    private Node<E> linkLastx(E e) {
        checkNotNull(e);

        size.incrementAndGet();

        final Node<E> newNode = new Node<E>(e);

        restartFromTail:
        for (;;) {
            for (Node<E> t = tail, p = t, q;;) {
                if ((q = p.next) != null && (q = (p = q).next) != null)
                    // Check for tail updates every other hop.
                    // If p == q, we are sure to follow tail instead.
                    p = (t != (t = tail)) ? t : q;
                else if (p.prev == p) // NEXT_TERMINATOR
                    continue restartFromTail;
                else {
                    // p is last node
                    newNode.lazySetPrev(p); // CAS piggyback.

                    if (p.casNext(null, newNode)) {
                        // Successful CAS is the linearization point
                        // for e to become an element of this deque,
                        // and for newNode to become "live".
                        if (p != t) // hop two nodes at a time
                            casTail(t, newNode);  // Failure is OK.

                        return newNode;
                    }
                    // Lost CAS race to another thread; re-read next
                }
            }
        }
    }

    /** Number of HOPs before unlinking head or tail. */
    private static final int HOPS = 2;

    /**
     * Unlinks non-null node x, that has not yet been unlinked.
     *
     * @param x Node.
     */
    public void unlinkx(Node<E> x) {
        assert x != null;

        E item = x.item;

        if (item != null && x.casItem(item, null))
            unlink(x);
    }

    /**
     * Unlinks non-null node x.
     */
    private void unlink(Node<E> x) {
        // assert x != null;
        // assert x.item == null;
        // assert x != PREV_TERMINATOR;
        // assert x != NEXT_TERMINATOR;

        final Node<E> prev = x.prev;
        final Node<E> next = x.next;

        // Unlink should not be called twice for the same node.
        size.decrementAndGet();

        if (prev == null)
            unlinkFirst(x, next);
        else if (next == null)
            unlinkLast(x, prev);
        else {
            // Unlink interior node.
            //
            // This is the common case, since a series of polls at the
            // same end will be "interior" removes, except perhaps for
            // the first one, since end nodes cannot be unlinked.
            //
            // At any time, all active nodes are mutually reachable by
            // following a sequence of either next or prev pointers.
            //
            // Our strategy is to find the unique active predecessor
            // and successor of x.  Try to fix up their links so that
            // they point to each other, leaving x unreachable from
            // active nodes.  If successful, and if x has no live
            // predecessor/successor, we additionally try to gc-unlink,
            // leaving active nodes unreachable from x, by rechecking
            // that the status of predecessor and successor are
            // unchanged and ensuring that x is not reachable from
            // tail/head, before setting x's prev/next links to their
            // logical approximate replacements, self/TERMINATOR.
            Node<E> activePred, activeSucc;

            boolean isFirst, isLast;

            int hops = 1;

            // Find active predecessor
            for (Node<E> p = prev; ; ++hops) {
                if (p.item != null) {
                    activePred = p;

                    isFirst = false;

                    break;
                }

                Node<E> q = p.prev;

                if (q == null) {
                    if (p.next == p)
                        return;

                    activePred = p;

                    isFirst = true;

                    break;
                }
                else if (p == q)
                    return;
                else
                    p = q;
            }

            // Find active successor
            for (Node<E> p = next; ; ++hops) {
                if (p.item != null) {
                    activeSucc = p;

                    isLast = false;

                    break;
                }

                Node<E> q = p.next;

                if (q == null) {
                    if (p.prev == p)
                        return;

                    activeSucc = p;

                    isLast = true;

                    break;
                }
                else if (p == q)
                    return;
                else
                    p = q;
            }

            // TODO: better HOP heuristics
            // Always squeeze out interior deleted nodes.
            if (hops < HOPS && (isFirst | isLast))
                return;

            // Squeeze out deleted nodes between activePred and
            // activeSucc, including x.
            skipDeletedSuccessors(activePred);
            skipDeletedPredecessors(activeSucc);

            // Try to gc-unlink, if possible
            if ((isFirst | isLast) &&
                // Recheck expected state of predecessor and successor
                (activePred.next == activeSucc) &&
                (activeSucc.prev == activePred) &&
                (isFirst ? activePred.prev == null : activePred.item != null) &&
                (isLast  ? activeSucc.next == null : activeSucc.item != null)) {

                updateHead(); // Ensure x is not reachable from head
                updateTail(); // Ensure x is not reachable from tail

                // Finally, actually gc-unlink
                x.lazySetPrev(isFirst ? prevTerminator() : x);
                x.lazySetNext(isLast  ? nextTerminator() : x);
            }
        }
    }

    /**
     * Unlinks non-null first node.
     */
    private void unlinkFirst(Node<E> first, Node<E> next) {
        // assert first != null;
        // assert next != null;
        // assert first.item == null;
        for (Node<E> o = null, p = next, q;;) {
            if (p.item != null || (q = p.next) == null) {
                if (o != null && p.prev != p && first.casNext(next, p)) {
                    skipDeletedPredecessors(p);
                    if (first.prev == null &&
                        (p.next == null || p.item != null) &&
                        p.prev == first) {

                        updateHead(); // Ensure o is not reachable from head
                        updateTail(); // Ensure o is not reachable from tail

                        // Finally, actually gc-unlink
                        o.lazySetNext(o);
                        o.lazySetPrev(prevTerminator());
                    }
                }
                return;
            }
            else if (p == q)
                return;
            else {
                o = p;
                p = q;
            }
        }
    }

    /**
     * Unlinks non-null last node.
     */
    private void unlinkLast(Node<E> last, Node<E> prev) {
        // assert last != null;
        // assert prev != null;
        // assert last.item == null;
        for (Node<E> o = null, p = prev, q;;) {
            if (p.item != null || (q = p.prev) == null) {
                if (o != null && p.next != p && last.casPrev(prev, p)) {
                    skipDeletedSuccessors(p);
                    if (last.next == null &&
                        (p.prev == null || p.item != null) &&
                        p.next == last) {

                        updateHead(); // Ensure o is not reachable from head
                        updateTail(); // Ensure o is not reachable from tail

                        // Finally, actually gc-unlink
                        o.lazySetPrev(o);
                        o.lazySetNext(nextTerminator());
                    }
                }
                return;
            }
            else if (p == q)
                return;
            else {
                o = p;
                p = q;
            }
        }
    }

    /**
     * Guarantees that any node which was unlinked before a call to
     * this method will be unreachable from head after it returns.
     * Does not guarantee to eliminate slack, only that head will
     * point to a node that was active while this method was running.
     */
    private final void updateHead() {
        // Either head already points to an active node, or we keep
        // trying to cas it to the first node until it does.
        Node<E> h, p, q;

        restartFromHead:
        while ((h = head).item == null && (p = h.prev) != null) {
            for (;;) {
                if ((q = p.prev) == null || (q = (p = q).prev) == null) {
                    // It is possible that p is PREV_TERMINATOR,
                    // but if so, the CAS is guaranteed to fail.
                    if (casHead(h, p))
                        return;
                    else
                        continue restartFromHead;
                }
                else if (h != head)
                    continue restartFromHead;
                else
                    p = q;
            }
        }
    }

    /**
     * Guarantees that any node which was unlinked before a call to
     * this method will be unreachable from tail after it returns.
     * Does not guarantee to eliminate slack, only that tail will
     * point to a node that was active while this method was running.
     */
    private final void updateTail() {
        // Either tail already points to an active node, or we keep
        // trying to cas it to the last node until it does.
        Node<E> t, p, q;

        restartFromTail:
        while ((t = tail).item == null && (p = t.next) != null) {
            for (;;) {
                if ((q = p.next) == null || (q = (p = q).next) == null) {
                    // It is possible that p is NEXT_TERMINATOR,
                    // but if so, the CAS is guaranteed to fail.
                    if (casTail(t, p))
                        return;
                    else
                        continue restartFromTail;
                }
                else if (t != tail)
                    continue restartFromTail;
                else
                    p = q;
            }
        }
    }

    /**
     * @param x Node to start from.
     */
    private void skipDeletedPredecessors(Node<E> x) {
        whileActive:
        do {
            Node<E> prev = x.prev;
            // assert prev != null;
            // assert x != NEXT_TERMINATOR;
            // assert x != PREV_TERMINATOR;
            Node<E> p = prev;

            findActive:
            for (;;) {
                if (p.item != null)
                    break findActive;

                Node<E> q = p.prev;

                if (q == null) {
                    if (p.next == p)
                        continue whileActive;

                    break findActive;
                }
                else if (p == q)
                    continue whileActive;
                else
                    p = q;
            }

            // found active CAS target
            if (prev == p || x.casPrev(prev, p))
                return;

        } while (x.item != null || x.next == null);
    }

    /**
     * @param x Node to start from.
     */
    private void skipDeletedSuccessors(Node<E> x) {
        whileActive:
        do {
            Node<E> next = x.next;
            // assert next != null;
            // assert x != NEXT_TERMINATOR;
            // assert x != PREV_TERMINATOR;
            Node<E> p = next;

            findActive:

            for (;;) {
                if (p.item != null)
                    break findActive;

                Node<E> q = p.next;

                if (q == null) {
                    if (p.prev == p)
                        continue whileActive;

                    break findActive;
                }
                else if (p == q)
                    continue whileActive;
                else
                    p = q;
            }

            // found active CAS target
            if (next == p || x.casNext(next, p))
                return;

        }
        while (x.item != null || x.prev == null);
    }

    /**
     * Returns the successor of p, or the first node if p.next has been
     * linked to self, which will only be true if traversing with a
     * stale pointer that is now off the list.
     *
     * @param p Node to find successor for.
     * @return Successor node.
     */
    final Node<E> successor(Node<E> p) {
        // TODO: should we skip deleted nodes here?
        Node<E> q = p.next;

        return (p == q) ? first() : q;
    }

    /**
     * Returns the predecessor of p, or the last node if p.prev has been
     * linked to self, which will only be true if traversing with a
     * stale pointer that is now off the list.
     *
     * @param p Node to find predecessor for.
     * @return Predecessor node.
     */
    final Node<E> predecessor(Node<E> p) {
        Node<E> q = p.prev;
        return (p == q) ? last() : q;
    }

    /**
     * Returns the first node, the unique node p for which:
     *     p.prev == null && p.next != p
     * The returned node may or may not be logically deleted.
     * Guarantees that head is set to the returned node.
     *
     * @return First node.
     */
    @SuppressWarnings( {"TooBroadScope"})
    Node<E> first() {
        restartFromHead:
        for (;;)
            for (Node<E> h = head, p = h, q;;) {
                if ((q = p.prev) != null &&
                    (q = (p = q).prev) != null)
                    // Check for head updates every other hop.
                    // If p == q, we are sure to follow head instead.
                    p = (h != (h = head)) ? h : q;
                else if (p == h
                         // It is possible that p is PREV_TERMINATOR,
                         // but if so, the CAS is guaranteed to fail.
                         || casHead(h, p))
                    return p;
                else
                    continue restartFromHead;
            }
    }

    /**
     * Returns the last node, the unique node p for which:
     *     p.next == null && p.prev != p
     * The returned node may or may not be logically deleted.
     * Guarantees that tail is set to the returned node.
     *
     * @return Last node.
     */
    @SuppressWarnings( {"TooBroadScope"})
    Node<E> last() {
        restartFromTail:
        for (;;)
            for (Node<E> t = tail, p = t, q;;) {
                if ((q = p.next) != null &&
                    (q = (p = q).next) != null)
                    // Check for tail updates every other hop.
                    // If p == q, we are sure to follow tail instead.
                    p = (t != (t = tail)) ? t : q;
                else if (p == t
                         // It is possible that p is NEXT_TERMINATOR,
                         // but if so, the CAS is guaranteed to fail.
                         || casTail(t, p))
                    return p;
                else
                    continue restartFromTail;
            }
    }

    /**
     * Throws NullPointerException if argument is null.
     *
     * @param v the element
     */
    private static void checkNotNull(Object v) {
        if (v == null)
            throw new NullPointerException();
    }

    /**
     * Returns element unless it is null, in which case throws
     * NoSuchElementException.
     *
     * @param v the element
     * @return the element
     */
    private E screenNullResult(E v) {
        if (v == null)
            throw new NoSuchElementException();

        return v;
    }

    /**
     * Creates an array list and fills it with elements of this list.
     * Used by toArray.
     *
     * @return the arrayList
     */
    private ArrayList<E> toArrayList() {
        ArrayList<E> list = new ArrayList<E>();

        for (Node<E> p = first(); p != null; p = successor(p)) {
            E item = p.item;

            if (item != null)
                list.add(item);
        }

        return list;
    }

    /**
     * Constructs an empty deque.
     */
    public GridConcurrentLinkedDeque() {
        head = tail = new Node<E>(null);
    }

    /**
     * Constructs a deque initially containing the elements of
     * the given collection, added in traversal order of the
     * collection's iterator.
     *
     * @param c the collection of elements to initially contain
     * @throws NullPointerException if the specified collection or any
     *         of its elements are null
     */
    public GridConcurrentLinkedDeque(Iterable<? extends E> c) {
        // Copy c into a private chain of Nodes
        Node<E> h = null, t = null;

        for (E e : c) {
            checkNotNull(e);

            Node<E> newNode = new Node<E>(e);

            if (h == null)
                h = t = newNode;
            else {
                t.lazySetNext(newNode);
                newNode.lazySetPrev(t);
                t = newNode;
            }
        }

        initHeadTail(h, t);
    }

    /**
     * Initializes head and tail, ensuring invariants hold.
     *
     * @param h Head.
     * @param t Tail.
     */
    private void initHeadTail(Node<E> h, Node<E> t) {
        if (h == t) {
            if (h == null)
                h = t = new Node<E>(null);
            else {
                // Avoid edge case of a single Node with non-null item.
                Node<E> newNode = new Node<E>(null);

                t.lazySetNext(newNode);

                newNode.lazySetPrev(t);

                t = newNode;
            }
        }

        head = h;
        tail = t;
    }

    /**
     * Inserts the specified element at the front of this deque.
     * As the deque is unbounded, this method will never throw
     * {@link IllegalStateException}.
     *
     * @throws NullPointerException if the specified element is null
     */
    @Override public void addFirst(E e) {
        linkFirst(e);
    }

    /**
     * Same as {@link #addFirst(Object)}, but returns new node.
     *
     * @param e Element to add.
     * @return New node.
     */
    public Node<E> addFirstx(E e) {
        return linkFirstx(e);
    }

    /**
     * Inserts the specified element at the end of this deque.
     * As the deque is unbounded, this method will never throw
     * {@link IllegalStateException}.
     *
     * <p>This method is equivalent to {@link #add}.
     *
     * @throws NullPointerException if the specified element is null
     */
    @Override public void addLast(E e) {
        linkLast(e);
    }

    /**
     * Same as {@link #addLast(Object)}, but returns new node.
     *
     * @param e Element to add.
     * @return New node.
     */
    public Node<E> addLastx(E e) {
        return linkLastx(e);
    }

    /**
     * Inserts the specified element at the front of this deque.
     * As the deque is unbounded, this method will never return {@code false}.
     *
     * @return {@code true} (as specified by {@link Deque#offerFirst})
     * @throws NullPointerException if the specified element is null
     */
    @Override public boolean offerFirst(E e) {
        linkFirst(e);

        return true;
    }

    /**
     * Same as {@link #offerFirst(Object)}, but returns new {@link Node}.
     *
     * @param e Element to add.
     * @return New node.
     */
    public Node<E> offerFirstx(E e) {
        return linkFirstx(e);
    }

    /**
     * Inserts the specified element at the end of this deque.
     * As the deque is unbounded, this method will never return {@code false}.
     *
     * <p>This method is equivalent to {@link #add}.
     *
     * @return {@code true} (as specified by {@link Deque#offerLast})
     * @throws NullPointerException if the specified element is null
     */
    @Override public boolean offerLast(E e) {
        linkLast(e);

        return true;
    }

    /**
     * Inserts the specified node at the end of this deque.
     * As the deque is unbounded, this method will never return {@code false}.
     *
     * <p>This method is equivalent to {@link #add(Node)}.
     *
     * @return {@code true} (as specified by {@link Deque#offerLast})
     * @throws NullPointerException if the node is null
     */
    public boolean offerLast(Node<E> n) {
        linkLast(n);

        return true;
    }

    /**
     * Same as {@link #offerLast(Object)}, but returns new {@link Node}.
     *
     * @param e Element to add.
     * @return New node.
     */
    public Node<E> offerLastx(E e) {
        return linkLastx(e);
    }

    /** {@inheritDoc} */
    @Override public @Nullable E peekFirst() {
        for (Node<E> p = first(); p != null; p = successor(p)) {
            E item = p.item;

            if (item != null)
                return item;
        }

        return null;
    }

    /** {@inheritDoc} */
    @Override public @Nullable E peekLast() {
        for (Node<E> p = last(); p != null; p = predecessor(p)) {
            E item = p.item;

            if (item != null)
                return item;
        }

        return null;
    }

    /**
     * @throws NoSuchElementException {@inheritDoc}
     */
    @Override public E getFirst() {
        return screenNullResult(peekFirst());
    }

    /**
     * @throws NoSuchElementException {@inheritDoc}
     */
    @Override public E getLast() {
        return screenNullResult(peekLast());
    }

    /** {@inheritDoc} */
    @Override public @Nullable E pollFirst() {
        for (Node<E> p = first(); p != null; p = successor(p)) {
            E item = p.item;

            if (item != null && p.casItem(item, null)) {
                unlink(p);

                return item;
            }
        }

        return null;
    }

    /**
     * Returns tuple of item and node or {@code null}
     * if deque is empty.
     *
     * @return Tuple of item and node or {@code null}.
     */
    public @Nullable GridTuple2<E, Node<E>> pollFirstx() {
        for (Node<E> p = first(); p != null; p = successor(p)) {
            E item = p.item;

            if (item != null && p.casItem(item, null)) {
                unlink(p);

                return F.t(item, p);
            }
        }

        return null;
    }

    /** {@inheritDoc} */
    @Override public @Nullable E pollLast() {
        for (Node<E> p = last(); p != null; p = predecessor(p)) {
            E item = p.item;

            if (item != null && p.casItem(item, null)) {
                unlink(p);

                return item;
            }
        }

        return null;
    }

    /**
     * @throws NoSuchElementException {@inheritDoc}
     */
    @Override public E removeFirst() {
        return screenNullResult(pollFirst());
    }

    /**
     * @throws NoSuchElementException {@inheritDoc}
     */
    @Override public E removeLast() {
        return screenNullResult(pollLast());
    }

    /**
     * Inserts the specified element at the tail of this deque.
     * As the deque is unbounded, this method will never return {@code false}.
     *
     * @return {@code true} (as specified by {@link Queue#offer})
     * @throws NullPointerException if the specified element is null
     */
    @Override public boolean offer(E e) {
        return offerLast(e);
    }

    /**
     * Same as {@link #offer(Object)}, but returns new {@link Node}.
     *
     * @param e Element to add.
     * @return New node.
     */
    public Node<E> offerx(E e) {
        return offerLastx(e);
    }

    /**
     * Inserts the specified element at the tail of this deque.
     * As the deque is unbounded, this method will never throw
     * {@link IllegalStateException} or return {@code false}.
     *
     * @return {@code true} (as specified by {@link Collection#add})
     * @throws NullPointerException if the specified element is null
     */
    @Override public boolean add(E e) {
        return offerLast(e);
    }

    /**
     * Inserts the specified node at the tail of this deque.
     * As the deque is unbounded, this method will never throw
     * {@link IllegalStateException} or return {@code false}.
     *
     * @return {@code true} (as specified by {@link Collection#add})
     * @throws NullPointerException if the specified element is null
     */
    public boolean add(Node<E> n) {
        return offerLast(n);
    }

    /**
     * Same as {@link #add(Object)}, but returns new node.
     *
     * @param e Element to add.
     * @return New node.
     */
    public Node<E> addx(E e) {
        return offerLastx(e);
    }

    /** {@inheritDoc} */
    @Override public E poll() {
        return pollFirst();
    }

    /**
     * Returns tuple of item and node or {@code null}
     * if deque is empty.
     *
     * @return Tuple of item and node or {@code null}.
     */
    @Nullable public GridTuple2<E, Node<E>> pollx() {
        return pollFirstx();
    }

    /** {@inheritDoc} */
    @Override public E remove() {
        return removeFirst();
    }

    /** {@inheritDoc} */
    @Override public @Nullable E peek() {
        return peekFirst();
    }

    /** {@inheritDoc} */
    @Override public E element() {
        return getFirst();
    }

    /** {@inheritDoc} */
    @Override public void push(E e) {
        addFirst(e);
    }

    /** {@inheritDoc} */
    @Override public E pop() {
        return removeFirst();
    }

    /**
     * Removes the first element {@code e} such that
     * {@code o.equals(e)}, if such an element exists in this deque.
     * If the deque does not contain the element, it is unchanged.
     *
     * @param o element to be removed from this deque, if present
     * @return {@code true} if the deque contained the specified element
     * @throws NullPointerException if the specified element is null
     */
    @Override public boolean removeFirstOccurrence(Object o) {
        checkNotNull(o);

        for (Node<E> p = first(); p != null; p = successor(p)) {
            E item = p.item;

            if (item != null && o.equals(item) && p.casItem(item, null)) {
                unlink(p);

                return true;
            }
        }

        return false;
    }

    /**
     * Removes the last element {@code e} such that
     * {@code o.equals(e)}, if such an element exists in this deque.
     * If the deque does not contain the element, it is unchanged.
     *
     * @param o element to be removed from this deque, if present
     * @return {@code true} if the deque contained the specified element
     * @throws NullPointerException if the specified element is null
     */
    @Override public boolean removeLastOccurrence(Object o) {
        checkNotNull(o);

        for (Node<E> p = last(); p != null; p = predecessor(p)) {
            E item = p.item;

            if (item != null && o.equals(item) && p.casItem(item, null)) {
                unlink(p);

                return true;
            }
        }

        return false;
    }

    /**
     * Returns {@code true} if this deque contains at least one
     * element {@code e} such that {@code o.equals(e)}.
     *
     * @param o element whose presence in this deque is to be tested
     * @return {@code true} if this deque contains the specified element
     */
    @Override public boolean contains(Object o) {
        if (o == null)
            return false;

        for (Node<E> p = first(); p != null; p = successor(p)) {
            E item = p.item;

            if (item != null && o.equals(item))
                return true;
        }

        return false;
    }

    /**
     * Returns {@code true} if this collection contains no elements.
     *
     * @return {@code true} if this collection contains no elements
     */
    @Override public boolean isEmpty() {
        return peekFirst() == null;
    }

    /**
     * Returns {@code true} if this collection contains no elements.
     * <p>
     * The difference from {@link #isEmpty()} method is that this method
     * relies on {@link #sizex()} method.
     *
     * @return {@code True} if this collection contains no elements
     */
    public boolean isEmptyx() {
        return sizex() == 0;
    }

    /**
     * Returns the number of elements in this deque.  If this deque
     * contains more than {@code Integer.MAX_VALUE} elements, it
     * returns {@code Integer.MAX_VALUE}.
     *
     * <p>Beware that, unlike in most collections, this method is
     * <em>NOT</em> a constant-time operation. Because of the
     * asynchronous nature of these deques, determining the current
     * number of elements requires traversing them all to count them.
     * Additionally, it is possible for the size to change during
     * execution of this method, in which case the returned result
     * will be inaccurate. Thus, this method is typically not very
     * useful in concurrent applications.
     *
     * @return the number of elements in this deque
     */
    @Override public int size() {
        int cnt = 0;

        for (Node<E> p = first(); p != null; p = successor(p))
            if (p.item != null)
                // Collection.size() spec says to max out
                if (++cnt == Integer.MAX_VALUE)
                    break;

        return cnt;
    }

    /**
     * @return Size based on performed operations.
     */
    public int sizex() {
        return size.get();
    }

    /**
     * Removes the first element {@code e} such that
     * {@code o.equals(e)}, if such an element exists in this deque.
     * If the deque does not contain the element, it is unchanged.
     *
     * @param o element to be removed from this deque, if present
     * @return {@code true} if the deque contained the specified element
     * @throws NullPointerException if the specified element is null
     */
    @Override public boolean remove(Object o) {
        return removeFirstOccurrence(o);
    }

    /**
     * Appends all of the elements in the specified collection to the end of
     * this deque, in the order that they are returned by the specified
     * collection's iterator.  Attempts to {@code addAll} of a deque to
     * itself result in {@code IllegalArgumentException}.
     *
     * @param c the elements to be inserted into this deque
     * @return {@code true} if this deque changed as a result of the call
     * @throws NullPointerException if the specified collection or any
     *         of its elements are null
     * @throws IllegalArgumentException if the collection is this deque
     */
    @SuppressWarnings( {"TooBroadScope"})
    @Override public boolean addAll(Collection<? extends E> c) {
        if (c == this)
            // As historically specified in AbstractQueue#addAll
            throw new IllegalArgumentException();

        // Copy c into a private chain of Nodes
        Node<E> beginningOfTheEnd = null, last = null;

        int s = 0;

        for (E e : c) {
            checkNotNull(e);

            Node<E> newNode = new Node<E>(e);

            if (beginningOfTheEnd == null) {
                beginningOfTheEnd = last = newNode;

                s++;
            }
            else {
                last.lazySetNext(newNode);

                newNode.lazySetPrev(last);

                last = newNode;

                s++;
            }
        }

        if (beginningOfTheEnd == null)
            return false;

        size.addAndGet(s);

        // Atomically append the chain at the tail of this collection
        restartFromTail:
        for (;;) {
            for (Node<E> t = tail, p = t, q;;) {
                if ((q = p.next) != null && (q = (p = q).next) != null)
                    // Check for tail updates every other hop.
                    // If p == q, we are sure to follow tail instead.
                    p = (t != (t = tail)) ? t : q;
                else if (p.prev == p) // NEXT_TERMINATOR
                    continue restartFromTail;
                else {
                    // p is last node
                    beginningOfTheEnd.lazySetPrev(p); // CAS piggyback

                    if (p.casNext(null, beginningOfTheEnd)) {
                        // Successful CAS is the linearization point
                        // for all elements to be added to this deque.
                        if (!casTail(t, last)) {
                            // Try a little harder to update tail,
                            // since we may be adding many elements.
                            t = tail;

                            if (last.next == null)
                                casTail(t, last);
                        }

                        return true;
                    }
                    // Lost CAS race to another thread; re-read next
                }
            }
        }
    }

    /**
     * Removes all of the elements from this deque.
     */
    @Override public void clear() {
        while (pollFirst() != null) {
            // No-op.
        }
    }

    /**
     * Returns an array containing all of the elements in this deque, in
     * proper sequence (from first to last element).
     *
     * <p>The returned array will be "safe" in that no references to it are
     * maintained by this deque.  (In other words, this method must allocate
     * a new array).  The caller is thus free to modify the returned array.
     *
     * <p>This method acts as bridge between array-based and collection-based
     * APIs.
     *
     * @return an array containing all of the elements in this deque
     */
    @Override public Object[] toArray() {
        return toArrayList().toArray();
    }

    /**
     * Returns an array containing all of the elements in this deque,
     * in proper sequence (from first to last element); the runtime
     * type of the returned array is that of the specified array.  If
     * the deque fits in the specified array, it is returned therein.
     * Otherwise, a new array is allocated with the runtime type of
     * the specified array and the size of this deque.
     *
     * <p>If this deque fits in the specified array with room to spare
     * (i.e., the array has more elements than this deque), the element in
     * the array immediately following the end of the deque is set to
     * {@code null}.
     *
     * <p>Like the {@link #toArray()} method, this method acts as
     * bridge between array-based and collection-based APIs.  Further,
     * this method allows precise control over the runtime type of the
     * output array, and may, under certain circumstances, be used to
     * save allocation costs.
     *
     * <p>Suppose {@code x} is a deque known to contain only strings.
     * The following code can be used to dump the deque into a newly
     * allocated array of {@code String}:
     *
     * <pre>
     *     String[] y = x.toArray(new String[0]);</pre>
     *
     * Note that {@code toArray(new Object[0])} is identical in function to
     * {@code toArray()}.
     *
     * @param a the array into which the elements of the deque are to
     *          be stored, if it is big enough; otherwise, a new array of the
     *          same runtime type is allocated for this purpose
     * @return an array containing all of the elements in this deque
     * @throws ArrayStoreException if the runtime type of the specified array
     *         is not a supertype of the runtime type of every element in
     *         this deque
     * @throws NullPointerException if the specified array is null
     */
    @SuppressWarnings( {"SuspiciousToArrayCall"})
    @Override public <T> T[] toArray(T[] a) {
        return toArrayList().toArray(a);
    }

    /**
     * Returns an iterator over the elements in this deque in proper sequence.
     * The elements will be returned in order from first (head) to last (tail).
     *
     * <p>The returned iterator is a "weakly consistent" iterator that
     * will never throw {@link java.util.ConcurrentModificationException
     * ConcurrentModificationException}, and guarantees to traverse
     * elements as they existed upon construction of the iterator, and
     * may (but is not guaranteed to) reflect any modifications
     * subsequent to construction.
     *
     * @return an iterator over the elements in this deque in proper sequence
     */
    @Override public Iterator<E> iterator() {
        return new Iter();
    }

    /**
     * Returns an iterator over the elements in this deque in reverse
     * sequential order.  The elements will be returned in order from
     * last (tail) to first (head).
     *
     * <p>The returned iterator is a "weakly consistent" iterator that
     * will never throw {@link java.util.ConcurrentModificationException
     * ConcurrentModificationException}, and guarantees to traverse
     * elements as they existed upon construction of the iterator, and
     * may (but is not guaranteed to) reflect any modifications
     * subsequent to construction.
     *
     * @return an iterator over the elements in this deque in reverse order
     */
    @Override public Iterator<E> descendingIterator() {
        return new DescendingIter();
    }

    /**
     * Abstract iterator.
     */
    private abstract class AbstractIter implements Iterator<E> {
        /**
         * Next node to return item for.
         */
        private Node<E> nextNode;

        /**
         * nextItem holds on to item fields because once we claim
         * that an element exists in hasNext(), we must return it in
         * the following next() call even if it was in the process of
         * being removed when hasNext() was called.
         */
        private E nextItem;

        /**
         * Node returned by most recent call to next. Needed by remove.
         * Reset to null if this element is deleted by a call to remove.
         */
        private Node<E> lastRet;

        /**
         * @return Starting node.
         */
        abstract Node<E> startNode();

        /**
         * @param p Node.
         * @return Next node.
         */
        abstract Node<E> nextNode(Node<E> p);

        /**
         * Advances to first element.
         */
        AbstractIter() {
            advance();
        }

        /**
         * Sets nextNode and nextItem to next valid node, or to null
         * if no such.
         */
        private void advance() {
            lastRet = nextNode;

            Node<E> p = (nextNode == null) ? startNode() : nextNode(nextNode);

            for (;; p = nextNode(p)) {
                if (p == null) {
                    // p might be active end or TERMINATOR node; both are OK
                    nextNode = null;
                    nextItem = null;

                    break;
                }

                E item = p.item;

                if (item != null) {
                    nextNode = p;
                    nextItem = item;

                    break;
                }
            }
        }

        /** {@inheritDoc} */
        @Override public boolean hasNext() {
            return nextItem != null;
        }

        /** {@inheritDoc} */
        @Override public E next() {
            E item = nextItem;

            if (item == null)
                throw new NoSuchElementException();

            advance();

            return item;
        }

        /** {@inheritDoc} */
        @Override public void remove() {
            Node<E> l = lastRet;

            if (l == null)
                throw new IllegalStateException();

            unlinkx(l);

            lastRet = null;
        }
    }

    /**
     * Forward iterator
     */
    private class Iter extends AbstractIter {
        /** {@inheritDoc} */
        @Override Node<E> startNode() {
            return first();
        }

        /** {@inheritDoc} */
        @Override Node<E> nextNode(Node<E> p) {
            return successor(p);
        }
    }

    /**
     * Descending iterator.
     */
    private class DescendingIter extends AbstractIter {
        /** {@inheritDoc} */
        @Override Node<E> startNode() {
            return last();
        }

        /** {@inheritDoc} */
        @Override Node<E> nextNode(Node<E> p) {
            return predecessor(p);
        }
    }

    /**
     * CAS for head.
     *
     * @param cmp Compare value.
     * @param val New value.
     * @return {@code True} if set.
     */
    private boolean casHead(Node<E> cmp, Node<E> val) {
        return UNSAFE.compareAndSwapObject(this, headOffset, cmp, val);
    }

    /**
     * CAS for tail.
     *
     * @param cmp Compare value.
     * @param val New value.
     * @return {@code True} if set.
     */
    private boolean casTail(Node<E> cmp, Node<E> val) {
        return UNSAFE.compareAndSwapObject(this, tailOffset, cmp, val);
    }

    /** Unsafe. */
    private static final Unsafe UNSAFE;

    /** Head offset. */
    private static final long headOffset;

    /** Tail offset. */
    private static final long tailOffset;

    /**
     * Initialize terminators using unsafe semantics.
     */
    static {
        PREV_TERMINATOR = new Node<Object>();
        PREV_TERMINATOR.next = PREV_TERMINATOR;
        NEXT_TERMINATOR = new Node<Object>();
        NEXT_TERMINATOR.prev = NEXT_TERMINATOR;

        try {
            UNSAFE = GridUnsafe.unsafe();

            Class cls = GridConcurrentLinkedDeque.class;

            headOffset = UNSAFE.objectFieldOffset(cls.getDeclaredField("head"));
            tailOffset = UNSAFE.objectFieldOffset(cls.getDeclaredField("tail"));
        }
        catch (Exception e) {
            throw new Error(e);
        }
    }
}
