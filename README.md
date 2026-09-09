# kotoba-lang/org-ieee-sort — POSIX `sort`, as a Kotoba command binary

`sort` from IEEE Std 1003.1 for one operand, in byte order, written in
`.kotoba` and compiled to a standalone native executable.

```sh
./sort FILE
```

## No ordering primitive was needed

The language has no string comparison but `string=`, which is why
[`org-ieee-ls`](https://github.com/kotoba-lang/org-ieee-ls) records `-a` as
needing "a merge into byte order" it could not do.

It does not need a primitive. **UTF-8 preserves code point order
lexicographically**, so walking both strings with `string-code-point-at` and
comparing code points *is* byte order. `less?` is that walk, and it is
ordinary guest code.

(It carries four parameters, not six. Six was `function parameters exceed
ABI-supported arity`; the two string lengths are recomputed each step
instead, which costs nothing — `string-byte-length` is a host callback.)

## Byte order, so the comparison is `LC_ALL=C sort`

Measured 2026-09-10:

| input | default locale | `LC_ALL=C` |
|---|---|---|
| `b a B A` | `a A b B` | `A B a b` |
| `日 é z` | `日 é z` | `z é 日` |

This produces the second in both cases. Comparing against the first would be
comparing two orderings.

`sort` also **adds** the newline a last line lacks — 13 bytes in, 14 out.
Same as `grep`, opposite of `head`.

## Measured against the system utility

Eleven cases, all byte-identical. Duplicates are kept (both of them),
`10 100 9` is lexical and not numeric, and a common prefix sorts before what
extends it.

Verified to fail as well as pass: making a prefix *not* less than its
extension fails exactly one case, and dropping all duplicates instead of one
per pass fails exactly one.

## The control that passed, and what it found

A third control removed a `trim-final-newline` the first version carried —
and **all nine cases still passed**, which said the code was dead. The walk
stops when the remainder is empty, so the piece after the last newline is
never produced as a line; the trim had nothing to do.

It was worse than dead. A file ending in `\n\n` has a genuinely **empty last
line**, and `sort` prints it. On `a\n\n`, `/usr/bin/sort` answers an empty
line then `a`; with the trim this answered `a` alone.

The fixture that catches it is now in the suite, and putting the trim back
fails exactly that one case. A control that discriminates nothing is not a
passing control — it is a question the fixtures could not answer.

## Selection sort, and why

Substrings are **views** and cost no arena bytes; `string-concat` is what
allocates, and the arena never reclaims. So the shape that matters is how
many concatenations happen, not how many comparisons. Selection sort emits
the minimum and rebuilds the remainder once per line, rather than building a
sorted accumulator that would concatenate on every comparison.

It is still O(n²) in allocations. Package with `--string-pool` and `--fuel`
that match the file you mean to sort; the suite uses 8 MB and 50,000,000.

## Capabilities

`:cli/args` (38), `:fs/app-data` (35), `:io/write` (37).

## Several files are MERGED, not sectioned

`sort` has no headers and no per-file structure: the operands are read
together and sorted as one, so the operand order does not change the output.
Duplicates survive — the same file twice yields every line twice.

### A last line with no newline stays whole

This is the case that separates reading operands as **lines** from
concatenating their **bytes**. Measured against `/usr/bin/sort` 2026-09-10,
where `nn.txt` holds `x` with no trailing newline:

```
sort nn.txt s2.txt         ->  a  c  x        `x` is its own line
cat nn.txt s2.txt | sort   ->  c  xa          `x` and `a` ran together
```

So each operand is terminated before the next is appended. The control is
precise: dropping that termination fails exactly the two cases where the
unterminated file is *followed* by another operand, and leaves
`["fruit" "nonl"]` passing — nothing follows it there to run into.

### An unreadable operand writes nothing at all

Not the readable operands, not a partial answer: `sort s1 nope s2` exits 2
with empty stdout, where `cat` and `wc` print what they could. So every
operand is checked **before** any is read. Removing that check fails all four
missing-operand cases.

## What this is not

Ascending, byte order. No `-r`, `-n`, `-u`, `-k`, `-f`, no reading standard
input — with no operand this exits 2 rather than pretending to have read an
empty one.
