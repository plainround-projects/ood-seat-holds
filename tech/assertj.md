# AssertJ 3.27.7

## What it is

AssertJ is a Java library for writing **assertions**: checks that a value is what you expect. An assertion that fails stops the test and prints a clear message with both the expected and the actual value. AssertJ's style is a **fluent** chain that reads like English: `assertThat(price).isEqualTo(expected)`. You start with `assertThat(actual)` and add checks after the dots.

## Why this repository uses it

The tests are meant to read as requirements. `assertThat(outcome).isEqualTo(new Reason("hold expired"))` reads as a sentence, and a failure message shows the two values side by side. JUnit's own assertions can do the job but are less descriptive; AssertJ's chains are what let a beginner read a test like prose.

## Where it appears

| Path | What it does with AssertJ |
| --- | --- |
| `pom.xml` | Declares `assertj-core` 3.27.7, scope `test`. |
| `src/test/java/**` | Every test class imports `org.assertj.core.api.Assertions.assertThat` (and often `assertThatThrownBy`). |

## How it works underneath

`assertThat(x)` is a static method that looks at the **type** of `x` and returns a matching assertion object: for a `String` you get string checks (`startsWith`, `contains`), for a `List` you get list checks (`hasSize`, `containsExactly`), for a number, number checks, for anything else, general object checks (`isEqualTo`, `isInstanceOf`). Each check method returns the same assertion object, so you can chain them. A failing check throws an `AssertionError` with a built message; JUnit (`junit.md`) catches it and marks the test as failed.

`isEqualTo` uses the object's `equals` method. That is why Java **records** are so convenient in tests: a record gets an `equals` that compares all its parts, so `assertThat(result).isEqualTo(new Booked(...))` compares the whole value.

## Walkthrough of this repository's use

The checks used in this repository, each with a real example. (Imports: `import static org.assertj.core.api.Assertions.assertThat;` lets you write `assertThat(...)` without the class name.)

### Equality and type

```java
@Test
void holdingAFreeSeatHoldsItForTenMinutes() {
  var outcome = service.hold(SEAT, ADA);

  assertThat(outcome).isEqualTo(new Success(new Held(ADA, MutableClock.START.plus(TEN_MINUTES))));
  assertThat(store.get(SEAT)).isEqualTo(new Held(ADA, MutableClock.START.plus(TEN_MINUTES)));
}
```

`assertThat(actual).isEqualTo(expected)` passes only if `actual.equals(expected)`. With records, that checks every field. `isInstanceOf(Type.class)` checks the kind of object without looking inside, for example `assertThat(result).isInstanceOf(Booked.class)`.

### Exceptions

```java
@Test
void anUnknownSeatIsAnError() {
  var unknown = new SeatId("nowhere");

  assertThatThrownBy(() -> store.get(unknown)).isInstanceOf(UnknownSeatException.class);
  assertThatThrownBy(() -> store.update(unknown, new Free(), new Sold(ADA)))
```

`assertThatThrownBy(() -> code)` runs the code inside the `() ->` (a **lambda**: a small anonymous function) and expects it to throw. You then check the exception: `isInstanceOf(...)` its type, `hasMessage(...)` its exact text, `hasMessageContaining(...)` part of its text. If the code does not throw, the test fails.

### Collections

```java
var all = store.seats();

assertThat(all)
    .as("the whole list is sorted, whatever else the store holds")
    .isSortedAccordingTo(Comparator.comparing(SeatId::value));
assertThat(all.stream().map(SeatId::value).filter(id -> id.startsWith(prefix)))
    .containsExactly(prefix + "a", prefix + "b", prefix + "c");
```

`containsExactly(a, b, c)` passes if the collection has exactly those items in exactly that order. `containsExactlyInAnyOrder` ignores order. `hasSize(n)`, `isEmpty()` and `doesNotHaveDuplicates()` do what they say. `extracting(function)` turns each item into one of its properties first, so you can check a list of names instead of a list of objects.

### Describing a failure

```java
assertThat(winners).as("round " + round).hasValue(1);
```

`.as("text")` attaches a description to the next check. When that check fails, the message starts with the description. Use it when a test makes several similar checks and you need to know which one failed.

### Numbers and money

Other checks seen in these tests: `isTrue()` and `isFalse()` for booleans, `isZero()` for numbers, `isSortedAccordingTo(comparator)` for ordering, `startsWith` and `contains` for text.

## Try it yourself

1. `grep -rn "assertThat" src/test | wc -l` counts the assertions in the tests.
2. In a scratch copy, change an expected value in a test and run `./mvnw -q test -Dtest=SeatTransitionsTest`: the message shows `Expecting ... to be equal to ...` with both values.
3. Change `containsExactly` to `containsExactlyInAnyOrder` in a copy of a test that has two items in the same order: it still passes. Swap the expected order and `containsExactly` fails.
4. `grep -rn "assertThatThrownBy" src/test | head -5` finds the exception checks.
5. `./mvnw dependency:tree | grep assertj` shows the library and its one dependency.

## Mistakes people make

- **Forgetting the final check.** `assertThat(x);` with nothing after it checks nothing and passes.
- **Comparing doubles for equality** or `BigDecimal` with different scales. This repository keeps money to two decimals and compares exact values.
- **Asserting an exception's message when only its type matters,** which makes tests brittle. Check the message only when the message is part of the behavior.
- **Using `==`** on objects instead of `isEqualTo`. `==` compares identity, not content.
- **Huge `containsExactly` lists** that hide what matters. Extract the field you care about first.

## Check yourself

1. *What does `assertThat(a).isEqualTo(b)` use to compare?* `a.equals(b)`.
2. *Why do records make assertions easier?* A record's automatic `equals` compares every field.
3. *How do you check that code throws?* `assertThatThrownBy(() -> code).isInstanceOf(...)`.
4. *What is the difference between `containsExactly` and `containsExactlyInAnyOrder`?* The first demands the same order; the second ignores order.
5. *What does `.as("...")` do?* Adds a description that appears if the next check fails.

## Further reading

- AssertJ Core 3.27.7 documentation: <https://assertj.github.io/doc/> (API: <https://www.javadoc.io/doc/org.assertj/assertj-core/3.27.7/>)
