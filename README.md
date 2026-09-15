# kotoba-lang/org-ieee-sort — POSIX `sort`, as a Kotoba command binary

`sort` from IEEE Std 1003.1, in byte order, written in `.kotoba` and compiled
to a standalone native executable.

```sh
./sort FILE...
./sort -r FILE...     # descending
./sort -u FILE...     # adjacent duplicates dropped after sorting
./sort -n FILE...     # by a leading numeric key
```

**One flag, and it is argument 0.** Combining them (`-rn`, `-r -u`, `-nu`) is
out of scope and is not implemented — see *What this is not*.

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

## The flag is argument 0's SHAPE, never the argument COUNT

`org-ieee-cut` asked `(= 3 count)` until 2026-09-10. That is right for
exactly one operand and silently wrong for two: `cut -f1 a b` counts 3 and
reads as carrying a `-d`. The bug is invisible until a second operand exists.

So this asks whether argument 0 begins with `-`, which keeps its answer when
a second operand shows up. Operand paths are absolute here, so they begin
with `/` and cannot collide.

The control is `flag-by-count`: replacing the shape test with
`(string=? (arg "") "2")` fails **19** cases, and it fails them in *both*
directions — the eleven two-operand cases with no flag now refuse as if they
carried one, and the eight three-argument flag cases now try to open `-r` as
a file. That is the sibling's bug, reproduced.

## `-n`, measured one shape at a time

Every one of these was a guess with an answer. The model that fits **all** of
them, and the only thing this implements:

> `key(line)` is the leading numeric prefix, after `[ \t]*` and an optional
> `-`. Ties on the key are broken by comparing the **whole line** in byte
> order.

Six things the measurement contradicted:

| assumption | measured `LC_ALL=C /usr/bin/sort -n` | so |
|---|---|---|
| `-n` is stable for equal keys | `1 01 " 1" 1x` → ` 1`, `01`, `1`, `1x` | it is **not** input order, it is byte order — the tie-break the merge applies |
| a leading `+` is a sign | `+5 3 +1 10` → `+1 +5 3 10` | `+` is **not** accepted; both plus lines are key 0 |
| blanks are whitespace | `\v7 \f6 5` → `\v7 \f6 5` | only **space and tab** are skipped; `\v` and `\f` leave the line non-numeric |
| the tiebreak is the part after the number | `1a 01b` → `01b 1a` | it is the **whole line** |
| `-0` is negative | `-0` vs `  0` → `  0` first | `-0` **equals** 0; the sign loses and the byte tiebreak decides |
| the key is a number | 30 ones vs 29 ones and a `2`, ordered right | it is **decimal digits**. No `i64` and no float can tell those apart |

And the rest, all reproduced:

- **not a number at all** → key 0, then the whole-line tiebreak.
  `banana 10 apple 9 2` → `apple banana 2 9 10`.
- **an empty line** → key 0, and first, because `""` is bytewise least.
  A blank-only line (`" "`) is also key 0 and sits after `""`.
- **leading whitespace** → space and tab skipped, then the number.
- **a leading `-`** → negative, but only if digits follow immediately:
  `- 5` is key 0, and a lone `-` is 0.
- **a leading numeric PREFIX**, not the whole line: `10abc 9xyz 2zzz` sorts
  `2zzz 9xyz 10abc`, `3abc9` is 3, and `1e3` is **1** — there is no exponent.
- **fractions**: `1.10 < 1.25 < 1.5`. `.5` is 0.5, `5.` is 5, `1.2.3` is 1.2,
  and `1.5 = 1.50 = 1.500` so the byte tiebreak orders them.
- **leading zeros** do not change the value: `007` and `7` tie, and `007`
  wins the tiebreak.

### What `-n` does NOT implement, and refuses rather than approximates

- **No exponent** (`1e3` is 1). This is not an exclusion — it is what the
  system utility does, measured.
- **No `+` sign, no `\v`/`\f` blanks.** Same: measured, not omitted.
- **No thousands separator and no locale decimal comma.** The suite runs
  `LC_ALL=C`, where neither exists. Under another locale this would be wrong
  and the README would be lying, which is why the locale is pinned.
- **No `-b`, `-k`, `-t`, `-g`, `-h`, `-V`.** The key is always the whole
  line's leading number.

The comparison is exact decimal, so it does not overflow and there is no
`text->i64` in this program: integer parts compare by significant-digit
count and then left to right, fractions compare digit by digit with a
missing digit reading as `0`.

That last rule looks untestable and is not. On the positive side, extra
fraction digits make a number both larger *and* bytewise later, so
"missing reads as 0" and "stop at the shorter fraction" agree on every
positive input. **Negated they disagree**: measured, `-1.5` and `-1.50001`
answer `-1.50001` first, where the byte tiebreak would have answered `-1.5`
first. The `nfracn` fixture is that shape, and it is the only reason the
rule is checked rather than assumed.

## Measured against the system utility

**98 cases**, all byte-identical on stdout, stderr and exit status.

### Every control, and exactly what it broke

Each is a deliberate single-line break, re-run, then restored.

| control | what it does | fails |
|---|---|---|
| `HARNESS-flag-as-path` | joins the flag onto the fixture directory in the *test* | **0 — see below** |
| `flag-by-count` | asks the argument count instead of argument 0's shape | 19: every two-operand case with no flag, and every three-argument case with one |
| `r-noop` | `-r` compares ascending | 12: every `-r` case whose answer is order-dependent |
| `u-nodedupe` | `-u` keeps duplicates | 7: exactly the `-u` cases that have a duplicate |
| `append-join` | restores the joined accumulator | 3: `-r` on `blank`, `trailblank`, `twoblank` |
| `n-lexical` | `-n` compares bytes | 20, all `-n` |
| `n-no-tiebreak` | `-n` gives up on an equal key | 17, all `-n`: every fixture with a tie |
| `n-minus-zero-negative` | `-0` sorts as negative | **1**: `nzero` |
| `n-vf-are-blanks` | `\v` and `\f` skipped as blanks | **1**: `nws` |
| `n-accept-plus` | a leading `+` reads as a sign | **2**: `nplus`, `nplus2` |
| `n-i64-parse` | integer parts through a `text->i64` digit walk | **1**: `nbig` (21 digits, and the walk overflows) |
| `n-frac-shortstop` | fractions stop at the shorter span | 3: `nfracn`, `nfracn2`, `ndot` |

The `-r` cases that survive `r-noop` are `one`, `empty`, `missing` and
`fruit missing` — one line, no lines, and two error paths. The `-u` cases
that survive `u-nodedupe` are the ones with nothing duplicated. Neither
survival is a gap; there is no order to get wrong.

### The control that passed, and what it found

`HARNESS-flag-as-path` is a control on the **test**, not on the program, and
it is the one that mattered. Path-joining `-r` onto the fixture directory
produces `/tmp/.../-r`, which does not exist — so *both* implementations fail
with the same `sort: No such file or directory` and the same exit 2, and the
suite reports **`{:ok true, :cases 98, :failed 0}`** with all 72 flag cases
green and none of them executing a flag.

That is one argument to the left of the bug this repo already carried: the
harness built argv as `[(first names)]` until 2026-09-10 and dropped every
operand past the first, so twelve multi-operand cases passed while printing
one file's lines. Same shape, same silence. The fix is that a name beginning
with `-` is passed through verbatim, and the evidence that it is load-bearing
is that removing it turns 72 real cases into 72 vacuous ones.

### One fixture that discriminates nothing, said plainly

`nbig2` is 31 ones against 30 ones and a `2`. **It survived all twelve
controls** — zero failures, against `nbig`'s two. A `text->i64` overflows there in a way that happens to preserve
the order, and a binary float collapses both to the same value and then falls
to the byte tiebreak, which also preserves the order. No wrong implementation
that could be built here fails it.

It is kept as a *witness* — it pins the 31-digit answer if someone later
swaps the digit compare for arithmetic — but it is not evidence, and calling
it evidence would be the thing this repo keeps warning about. **`nbig` is the
fixture that carries the discrimination**: 21 digits, and `n-i64-parse` fails
exactly it.

## The bug `-r` found in code that was already green

`append-line` used to **join**: an empty accumulator meant "nothing appended
yet", so it emitted the line alone rather than a separator and the line.

An accumulator holding **one empty line** is also the empty string. The two
were the same value, and the line was lost.

Ascending never showed it. An empty line is the smallest line there is, so it
is emitted first and never sits alone in the remainder — all 25 cases passed.
`-r` puts it **last**: on `a\n\n` this answered `a` where `/usr/bin/sort`
answers `a` and then an empty line.

It now **terminates** rather than joins, so "no lines" and "one empty line"
are the distinct values `""` and `"\n"`. It costs the same two
concatenations. `append-join` restores the old form and fails exactly the
three `-r` cases with an empty line.

## Merge sort on the loader's tail append (2026-09-15)

Substrings are **views** and cost no arena bytes; `string-concat` is what
allocates, and the arena never reclaims. The first shape was a selection
sort that emitted the minimum and rebuilt the remainder once per line —
O(n) copies of the remainder per line, quadratic in the pool. Measured: a
4,600-line, 200 KB file trapped.

Now a top-down merge sort over byte ranges of the terminated text. A range
is split at the line boundary nearest its middle (found by walking newlines
from its start — one host search per line per level, which the merge pays
anyway) and the two halves merged onto a run built with amu's **tail
append**: `string-concat` copies only its second operand when the first is
the pool's last allocation, so appending a line *view* to the run in
progress costs that line's bytes. Nothing between two appends allocates —
`string-index-of`, the views and `string-code-point-at` do not — which is
what keeps the run at the tail. Comparisons see each line **without** its
newline, since with it a line holding a byte below 10 (a tab) would sort
after a line it is a prefix of.

Every level allocates the text once: n · log₂(lines) pool bytes. Handles
(2026-09-16): **two per line per level** — the taken view and the appended
run. Byte order compares **in place** from the two line starts
(`line-less-from`, a newline ending a line), so no view is cut for the
comparison, and every scalar step (`line-after`'s search view, `-n`'s two
key views, the split's boundary walk) is a region (`arena-scope`, context
ABI v6) released as it answers. Measured on 3.3 MB / 76,940 lines: 11.7 Mi
handles before, 2.59 Mi after.

Measured, output identical to `LC_ALL=C /usr/bin/sort`: 3.3 MB 0.95 s user
(`-r` 0.97, `-n` 2.80); **33 MB / 769,400 lines 12.5 s** (`-u` 12.1), where
before it trapped on the pair ceiling after 5.5 s. `-n` at 33 MB exhausts a
4 × 10⁹ fuel budget (23 s of CPU): the numeric key walk is a function call
per digit. The system sort takes 0.38 s on that file. What costs the time
is the comparison — one `string-code-point-at` call per code point per
line, and lines that share a long prefix pay it every level — so a
host-side byte comparison is the next lever, as the host search was for
grep.

The numeric key is still a pair of substring **views** over the line and
performs no concatenation. The suite packages `--pairs 67108864
--string-pool 268435456 --cpu-seconds 120`.

## Capabilities

`:cli/args` (38), `:fs/app-data` (35), `:io/write` (37), `:io/write-error`
(39).

## Several files are MERGED, not sectioned

`sort` has no headers and no per-file structure: the operands are read
together and sorted as one, so the operand order does not change the output.
Duplicates survive — the same file twice yields every line twice, and under
`-u` it yields one copy.

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

Measured again with each flag: `sort -r nope`, `sort -u nope` and
`sort -n nope` all answer identically — exit 2, empty stdout, and
`sort: No such file or directory` — so the flag does not change this path.

## What this is not

- **No combined flags.** `-rn`, `-nu`, `-r -u` and `-un` are out of scope.
  Argument 0 is one of `-r`, `-u`, `-n` or it is an operand.
- **No `-k`, `-t`, `-f`, `-b`, `-c`, `-m`, `-o`, `-s`, `-g`, `-h`, `-V`.**
- **No reading standard input.** With no operand — and with a flag and no
  operand — this exits 2 rather than pretending to have read an empty one.
  There is no stdin capability. This is also why there is no test case for
  it: `spawnSync` closes the child's stdin, so `/usr/bin/sort` would see EOF
  and succeed, and the two would be answering different questions.
- **`-` is not standard input** either; it is rejected as an unknown flag.
- **An unknown flag is not reproduced byte for byte.** `/usr/bin/sort -x`
  exits 2 with `sort: invalid option -- x` followed by the whole BSD `Usage:`
  block, and `--bogus` words it differently again. That block is a
  BSD-`sort`-version-specific string; copying it would pin this suite to one
  build of one utility. This exits 2 and writes `sort: invalid option`, which
  is **not** byte-identical, and there is therefore **no test case for it** —
  named here rather than left for someone to discover.
