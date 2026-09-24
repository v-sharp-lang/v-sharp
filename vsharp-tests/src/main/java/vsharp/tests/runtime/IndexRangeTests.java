package vsharp.tests.runtime;

import java.util.Arrays;
import java.util.List;
import vsharp.runtime.VsIndex;
import vsharp.runtime.VsRange;
import vsharp.runtime.VsSlices;
import vsharp.runtime.VsTuple2;
import vsharp.runtime.VsTuple3;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// `System.Index`, `System.Range`, slicing and value tuples.
///
/// The lowering targets are exercised directly here, so backend tests only have to prove
/// the compiler emits the right calls, not that the calls behave.
public final class IndexRangeTests implements TestSuite {

    @Override
    public String suiteName() {
        return "runtime.indexrange";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("index from start resolves to itself", () ->
                Assert.equal(2L, VsIndex.fromStart(2).getOffset(5), "index without ^"));

        registry.test("index from end counts backwards", () -> {
            Assert.equal(4L, VsIndex.fromEnd(1).getOffset(5), "^1 is the last element");
            Assert.equal(5L, VsIndex.END.getOffset(5), "^0 is one past the last element");
            Assert.equal(0L, VsIndex.START.getOffset(5), "0 is the first element");
        });

        registry.test("index rejects negative values", () ->
                Assert.throwsException(IndexOutOfBoundsException.class,
                        () -> VsIndex.fromStart(-1), "negative index"));

        registry.test("index renders like C#", () -> {
            Assert.equal("2", VsIndex.fromStart(2).toString(), "from start");
            Assert.equal("^1", VsIndex.fromEnd(1).toString(), "from end");
        });

        registry.test("range resolves offset and length", () -> {
            int[] pair = new VsRange(VsIndex.fromStart(1), VsIndex.fromEnd(1)).getOffsetAndLength(5);
            Assert.equalList(List.of(1, 3), boxed(pair), "1..^1");
            Assert.equalList(List.of(0, 5), boxed(VsRange.ALL.getOffsetAndLength(5)), "..");
            Assert.equalList(List.of(2, 3), boxed(VsRange.startAt(VsIndex.fromStart(2))
                    .getOffsetAndLength(5)), "2..");
            Assert.equalList(List.of(0, 2), boxed(VsRange.endAt(VsIndex.fromStart(2))
                    .getOffsetAndLength(5)), "..2");
        });

        registry.test("range rejects an inverted or oversized span", () -> {
            Assert.throwsException(IndexOutOfBoundsException.class,
                    () -> new VsRange(VsIndex.fromStart(3), VsIndex.fromStart(1))
                            .getOffsetAndLength(5), "end before start");
            Assert.throwsException(IndexOutOfBoundsException.class,
                    () -> VsRange.startAt(VsIndex.fromStart(6)).getOffsetAndLength(5),
                    "start past the end");
        });

        registry.test("element access validates the resolved offset", () -> {
            Assert.equal(4L, VsSlices.offset(VsIndex.fromEnd(1), 5), "^1 of length 5");
            Assert.throwsException(IndexOutOfBoundsException.class,
                    () -> VsSlices.offset(VsIndex.END, 5), "^0 is not a valid element");
        });

        registry.test("string slicing matches C# substring semantics", () -> {
            Assert.equal("ell", VsSlices.slice("hello",
                    new VsRange(VsIndex.fromStart(1), VsIndex.fromStart(4))), "1..4");
            Assert.equal("hello", VsSlices.slice("hello", VsRange.ALL), "..");
            Assert.equal("", VsSlices.slice("hello",
                    new VsRange(VsIndex.fromStart(2), VsIndex.fromStart(2))), "empty slice");
            Assert.equal("hell", VsSlices.slice("hello",
                    VsRange.endAt(VsIndex.fromEnd(1))), "..^1");
        });

        registry.test("array slicing copies rather than views", () -> {
            int[] source = {0, 1, 2, 3, 4};
            int[] slice = VsSlices.slice(source,
                    new VsRange(VsIndex.fromStart(1), VsIndex.fromEnd(1)));
            Assert.equalList(List.of(1, 2, 3), boxed(slice), "1..^1");
            slice[0] = 99;
            Assert.equal(1L, source[1], "the source array is untouched");
        });

        registry.test("reference array slicing keeps the runtime component type", () -> {
            String[] source = {"a", "b", "c"};
            String[] slice = VsSlices.slice(source, VsRange.startAt(VsIndex.fromStart(1)));
            Assert.equal(String[].class, slice.getClass(), "component type");
            Assert.equalList(List.of("b", "c"), List.of(slice), "1..");
        });

        registry.test("tuples use structural equality and C# rendering", () -> {
            Assert.equal(new VsTuple2<>(1, "a"), new VsTuple2<>(1, "a"), "equal tuples");
            Assert.isFalse(new VsTuple2<>(1, "a").equals(new VsTuple2<>(2, "a")), "unequal tuples");
            Assert.equal("(1, a)", new VsTuple2<>(1, "a").toString(), "arity 2");
            Assert.equal("(1, True, )", new VsTuple3<>(1, true, null).toString(),
                    "elements render with C# rules, including null");
        });
    }

    /// Boxes an `int[]` so assertions can report differences element-wise.
    private static List<Integer> boxed(int[] values) {
        return Arrays.stream(values).boxed().toList();
    }
}
