<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

## Comparison: chemical machine vs. academic Join Calculus

In talking about `Chymyst`, I follow the chemical machine metaphor and terminology, which differs from the terminology usually found in academic papers on JC.
Here is a dictionary:

| Chemical machine  | Academic Join Calculus | `Chymyst` code |
|---|---|---|
| input molecule | message on channel | `case a(123) => ...` _// pattern-matching_ |
| molecule emitter | channel name | `val a :  M[Int]` |
| blocking emitter | synchronous channel | `val q :  B[Unit, Int]` |
| reaction | process | `val r1 = go { case a(x) + ... => ... }` |
| emitting an output molecule | sending a message | `a(123)` _// side effect_ |
| emitting a blocking molecule | sending a synchronous message | `q()` _// returns Int_ |
| reaction site | join definition | `site(r1, r2, ...)` |

As another comparison, here is some code in academic Join Calculus, taken from [this tutorial](http://research.microsoft.com/en-us/um/people/fournet/papers/join-tutorial.pdf):

<img alt="def newVar(v0) def put(w) etc." src="docs/academic_join_calculus_2.png" width="400" />

This code creates a shared value container `val` with synchronized single access.

The equivalent `Chymyst` code looks like this:

```scala
def newVar[T](v0: T): (B[T, Unit], B[Unit, T]) = {
  val put = b[T, Unit] 
  val get = b[Unit, T]
  val _val = m[T] // Will use the name `_val` since `val` is a Scala keyword.
  
  site(
    go { case put(w, ret) + _val(v) => _val(w); ret() },
    go { case get(_, ret) + _val(v) => _val(v); ret(v) }
  )
  _val(v0)
  
  (put, get)
}

```

### Extensions to Join Calculus

`Chymyst` implements significantly fewer restrictions than usually present in academic versions of Join Calculus:

- reactions can have arbitrary guard conditions on molecule values
- reactions can consume several molecules of the same sort ("nonlinear input pattern")
- reactions can consume an arbitrary number of blocking and non-blocking input molecules, and each blocking input molecule can receive its own reply ("nonlinear reply")

`Chymyst` also implements some additional features that are important for practical applications but not included by the academic Join Calculus:

- timeouts on blocking calls
- being able to terminate a reaction site, in order to make the program stop


## Comparison: chemical machine vs. Actor model

Chemical machine programming is similar in some aspects to the well-known Actor model (e.g. the [Akka framework](https://github.com/akka/akka)).

| Chemical machine | Actor model |
|---|---|
| molecules carry values | messages carry values | 
| reactions wait to receive certain molecules | actors wait to receive certain messages | 
| synchronization is implicit in molecule emission | synchronization is implicit in message-passing | 
| reactions start when molecules are available | actors start running when a message is received |

Main differences between the chemical machine and the Actor model:

| Chemical machine | Actor model |
|---|---|
| several concurrent reactions start automatically whenever several input molecules are available | a desired number of concurrent actors must be created and managed manually |
| the user's code only manipulates molecules | the user's code must manipulate explicit references to actors as well as messages |
| reactions typically wait for (and consume) several input molecules at once | actors wait for (and consume) only one input message at a time |
| reactions are immutable and stateless, all data is stored on molecules (which are also immutable) | actors can mutate (“become another actor”); actors can hold mutable state |
| molecules are held in an unordered bag and processed in random order | messages are held in an ordered queue (mailbox) and processed in the order received |
| molecule data is statically typed | message data is untyped |

## Comparison: chemical machine vs. CSP

CSP (Communicating Sequential Processes) is another approach to declarative concurrency, used today in the Go programming language.

Similarities:

The channels of CSP are similar to blocking molecules: sending a message will block until a process can be started that consumes the message and replies with a value.

Differences:

The chemical machine admits only one reply to a blocking channel; CSP can open a channel and send many messages to it.

The chemical machine will start processes automatically and concurrently whenever input molecules are available.
In CSP, the user needs to create and manage new threads manually.

JC has non-blocking channels as a primitive construct.
In CSP, non-blocking channels need to be simulated by [additional user code](https://gobyexample.com/non-blocking-channel-operations).

