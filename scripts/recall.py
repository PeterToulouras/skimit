#!/usr/bin/env python3
"""Recall test: inject known changes into a program and see whether skimit says something.

Each mutation is written before any result is seen. A "behaviour" mutation changes what a way in
does or how it does it, and skimit should say so; a "same" mutation changes the code but not the
behaviour, and skimit should say "No change in behaviour."; a "read" mutation changes a read path,
which skimit leaves out by design.

    scripts/recall.py [--out FILE]                       # the example shop, compiled with javac
    scripts/recall.py --project DIR --mutations FILE.json --build CMD --classes PATH...
                      [--policy FILE] [--out FILE]       # a real project, built with its own build

A mutation file is a JSON list of {"id", "kind", "says", "edits": [[file, old, new]...],
"moves": [[from, to]...]}; each `old` must occur exactly once in its file. The base program is
built once; each mutation is applied to a copy of the sources, built, and compared with the base.
SKIMIT_JAR points at a jar other than the built one. The result is a Markdown table.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SHOP = ROOT / "examples" / "shop"
JAR = Path(os.environ.get("SKIMIT_JAR", ROOT / "skimit-analyzer" / "target" / "skimit.jar"))

CHARGE = "    payments.charge(order.customer(), prices.total(order));\n"
REFUND = "    ledger.post(customer, -cents);\n"
GUARD = "    if (!permissions.canRefund(user)) return false;\n"

# The example shop's mutations: (id, kind, what a reader should hear, [(file, old, new)...], [(from, to)...])
SHOP_MUTATIONS = [
    # --- changes to behaviour: skimit should say something -------------------------------------
    ("remove_auth_check", "behaviour", "refunds no longer need permission",
     [("src/shop/support/RefundHandler.java", GUARD, "")]),
    ("invert_auth_check", "behaviour", "only people WITHOUT permission can refund",
     [("src/shop/support/RefundHandler.java", "if (!permissions.canRefund(user)) return false;",
       "if (permissions.canRefund(user)) return false;")]),
    ("get_route_writes", "behaviour", "a report route now moves money",
     [("src/shop/reports/SalesReport.java", "  private final TextTable table = new TextTable();\n",
       "  private final TextTable table = new TextTable();\n  private final shop.billing.Ledger ledger = new shop.billing.Ledger();\n"),
      ("src/shop/reports/SalesReport.java", "      sb.append(table.pad(o.customer(), 20)).append(o.quantity()).append('\\n');\n",
       "      sb.append(table.pad(o.customer(), 20)).append(o.quantity()).append('\\n');\n      ledger.post(o.customer(), -500);\n")]),
    ("new_write_route", "behaviour", "a new route credits accounts",
     [("src/shop/support/RefundHandler.java", REFUND + "    return true;\n  }\n",
       REFUND + "    return true;\n  }\n\n  @PostMapping(\"/credits\")\n  public void credit(String customer, long cents) {\n"
       "    ledger.post(customer, cents);\n  }\n")]),
    ("remove_write", "behaviour", "checkout no longer charges anyone",
     [("src/shop/checkout/CheckoutHandler.java", CHARGE, "    prices.total(order);\n")]),
    ("price_doubles", "behaviour", "every checkout charges twice the price",
     [("src/shop/checkout/PriceCalculator.java", "return order.unitCents() * order.quantity();",
       "return order.unitCents() * order.quantity() * 2;")]),
    ("refund_charges", "behaviour", "a refund now charges instead of crediting",
     [("src/shop/support/RefundHandler.java", REFUND, "    ledger.post(customer, cents);\n")]),
    ("refund_to_wrong_account", "behaviour", "the refund goes to the admin, not the customer",
     [("src/shop/support/RefundHandler.java", REFUND, "    ledger.post(user, -cents);\n")]),
    ("add_quantity_check", "behaviour", "empty orders are no longer charged",
     [("src/shop/checkout/CheckoutHandler.java", CHARGE,
       "    if (order.quantity() > 0) payments.charge(order.customer(), prices.total(order));\n")]),
    ("widen_permission", "behaviour", "support staff can refund too",
     [("src/shop/auth/Permissions.java", "return user.startsWith(\"admin:\");",
       "return user.startsWith(\"admin:\") || user.startsWith(\"support:\");")]),
    ("sink_drops_amount", "behaviour", "the ledger no longer records the amount",
     [("src/shop/billing/Ledger.java", "entries.add(account + \":\" + cents);", "entries.add(account);")]),
    ("report_calls_out", "behaviour", "the report route now calls another service",
     [("src/shop/reports/SalesReport.java", "    return sb.toString();\n  }\n}\n",
       "    try {\n      java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder("
       "java.net.URI.create(\"http://audit.local/\" + orders.size())).build(), java.net.http.HttpResponse.BodyHandlers.discarding());\n"
       "    } catch (Exception e) {\n      throw new RuntimeException(e);\n    }\n    return sb.toString();\n  }\n}\n")]),
    ("charge_twice", "behaviour", "checkout charges a second time, a flat fee",
     [("src/shop/checkout/CheckoutHandler.java", CHARGE, CHARGE + "    payments.charge(order.customer(), 100);\n")]),
    ("refund_cap", "behaviour", "refunds over a limit are refused",
     [("src/shop/support/RefundHandler.java", REFUND, "    if (cents > 100000) return false;\n" + REFUND)]),
    ("write_via_reflection", "behaviour", "the same write, made through reflection",
     [("src/shop/support/RefundHandler.java", REFUND,
       "    try {\n      Ledger.class.getMethod(\"post\", String.class, long.class).invoke(ledger, customer, -cents);\n"
       "    } catch (ReflectiveOperationException e) {\n      throw new IllegalStateException(e);\n    }\n")]),
    # --- the same behaviour, written differently: skimit should say nothing ----------------------
    ("rename_local", "same", "a parameter renamed",
     [("src/shop/support/RefundHandler.java", "String customer, long cents) {", "String customer, long amount) {"),
      ("src/shop/support/RefundHandler.java", REFUND, "    ledger.post(customer, -amount);\n")]),
    ("reformat_and_comment", "same", "a comment and line breaks",
     [("src/shop/checkout/CheckoutHandler.java", "  @PostMapping(\"/checkout\")\n  public void submit(Order order) {\n" + CHARGE + "  }",
       "  // Charges the customer the computed total.\n  @PostMapping(\"/checkout\")\n  public void submit(Order order) {\n\n"
       "    payments.charge(\n        order.customer(),\n        prices.total(order));\n\n  }")]),
    ("extract_method", "same", "the charge moved into a private helper",
     [("src/shop/checkout/CheckoutHandler.java", CHARGE + "  }\n",
       "    charge(order);\n  }\n\n  private void charge(Order order) {\n" + CHARGE + "  }\n")]),
    ("move_class_package", "same", "Permissions moved to another package",
     [("src/shop/auth/Permissions.java", "package shop.auth;", "package shop.security;"),
      ("src/shop/support/RefundHandler.java", "import shop.auth.Permissions;", "import shop.security.Permissions;")],
     [("src/shop/auth/Permissions.java", "src/shop/security/Permissions.java")]),
    ("add_logging", "same", "a line printed before the charge",
     [("src/shop/checkout/CheckoutHandler.java", CHARGE, "    System.out.println(\"checkout \" + order.customer());\n" + CHARGE)]),
    ("unused_param", "same", "a parameter added and never used",
     [("src/shop/checkout/PriceCalculator.java", "public long total(Order order) {", "public long total(Order order, boolean verbose) {"),
      ("src/shop/checkout/CheckoutHandler.java", "prices.total(order)", "prices.total(order, false)")]),
    ("rename_method", "same", "PaymentService.charge renamed to pay",
     [("src/shop/billing/PaymentService.java", "void charge(", "void pay("),
      ("src/shop/billing/CardPaymentService.java", "public void charge(", "public void pay("),
      ("src/shop/checkout/CheckoutHandler.java", "payments.charge(", "payments.pay(")]),
    ("commute_multiplication", "same", "the two factors of the price swapped",
     [("src/shop/checkout/PriceCalculator.java", "order.unitCents() * order.quantity()", "order.quantity() * order.unitCents()")]),
    ("field_to_local", "same", "the calculator made where it is used instead of held in a field",
     [("src/shop/checkout/CheckoutHandler.java", "  private final PriceCalculator prices = new PriceCalculator();\n", ""),
      ("src/shop/checkout/CheckoutHandler.java", "prices.total(order)", "new PriceCalculator().total(order)")]),
    # --- a change to a read path: left out by design --------------------------------------------
    ("read_path_rewrite", "read", "the report pads its columns another way",
     [("src/shop/reports/TextTable.java",
       "    StringBuilder sb = new StringBuilder(s);\n    while (sb.length() < width) sb.append(' ');\n    return sb.toString();\n",
       "    if (s.length() >= width) return s.substring(0, width);\n    return s + \" \".repeat(width - s.length());\n")]),
]


def run(*cmd, cwd=None, shell=False):
    r = subprocess.run(cmd if not shell else cmd[0], cwd=cwd, capture_output=True, text=True, shell=shell)
    if r.returncode != 0:
        raise SystemExit(f"recall: failed: {cmd}\n{r.stdout[-4000:]}{r.stderr[-4000:]}")
    return r.stdout


def javac(out, cp, sources):
    out.mkdir(parents=True, exist_ok=True)
    # With local variable names, as Maven and Gradle compile by default.
    run("javac", "-g", "-cp", os.pathsep.join(map(str, cp)), "-d", str(out), *map(str, sources))


class Shop:
    """The example shop: its own classes, compiled against the annotation and the Spring stand-ins."""

    def __init__(self, work):
        self.source = SHOP
        self.policy = SHOP / "shop.policy"
        self.mutations = [dict(id=m[0], kind=m[1], says=m[2], edits=m[3], moves=m[4] if len(m) > 4 else []) for m in SHOP_MUTATIONS]
        self.ann = work / "ann"
        javac(self.ann, [], sorted((ROOT / "skimit-annotations" / "src" / "main" / "java").rglob("*.java")))
        self.spring = work / "spring"
        javac(self.spring, [self.ann], sorted((SHOP / "spring").rglob("*.java")))

    def build(self, tree):
        out = tree / "classes"
        shutil.rmtree(out, ignore_errors=True)
        javac(out, [self.ann, self.spring], sorted(Path(tree, "src").rglob("*.java")))
        return [out]


class Project:
    """A real project: copied without its build output, built with the command given."""

    def __init__(self, source, mutations, build, classes, policy):
        self.source = Path(source).resolve()
        self.policy = Path(policy).resolve() if policy else None
        self.mutations = json.loads(Path(mutations).read_text())
        self.command = build
        self.classes = classes

    def build(self, tree):
        run(self.command, cwd=tree, shell=True)
        out = [tree / c for c in self.classes]
        missing = [str(c) for c in out if not c.is_dir()]
        if missing:
            raise SystemExit(f"recall: no classes at {missing} after the build")
        return out


def copy(source, dest):
    shutil.copytree(source, dest, symlinks=True, ignore=shutil.ignore_patterns(".git", "target", "build", "node_modules", "classes"))


def mutate(base, dest, edits, moves):
    copy(base, dest)
    renamed = {src: to for src, to in moves}
    for src, to in moves:
        (dest / to).parent.mkdir(parents=True, exist_ok=True)
        (dest / src).rename(dest / to)
    for file, old, new in edits:
        p = dest / renamed.get(file, file)
        text = p.read_text()
        if text.count(old) != 1:
            raise SystemExit(f"recall: {file}: expected exactly one occurrence of {old!r}, found {text.count(old)}")
        p.write_text(text.replace(old, new))


def main():
    args = sys.argv[1:]
    opts = {"--classes": []}
    while args:
        a = args.pop(0)
        if a == "--classes":
            while args and not args[0].startswith("--"):
                opts["--classes"].append(args.pop(0))
        elif a.startswith("--"):
            opts[a] = args.pop(0)
        else:
            raise SystemExit(__doc__)
    if not JAR.exists():
        raise SystemExit(f"recall: {JAR} not found; run mvn package or set SKIMIT_JAR")
    work = Path(tempfile.mkdtemp(prefix="skimit-recall-"))
    try:
        if "--project" in opts:
            target = Project(opts["--project"], opts["--mutations"], opts["--build"], opts["--classes"], opts.get("--policy"))
        else:
            target = Shop(work)
        base = work / "base"
        copy(target.source, base)
        base_classes = target.build(base)
        rows = []
        for m in target.mutations:
            head = work / m["id"]
            mutate(base, head, m["edits"], m.get("moves", []))
            head_classes = target.build(head)
            result = work / f"{m['id']}.json"
            cmd = ["java", "-jar", str(JAR), "diff", "--base", *map(str, base_classes), "--head", *map(str, head_classes), "--json", str(result)]
            if target.policy:
                cmd += ["--policy", str(target.policy)]
            run(*cmd)
            lines = [l["text"] for l in json.loads(result.read_text())["lines"]]
            spoke = bool(lines)
            expected = m["kind"] == "behaviour"
            verdict = ("caught" if spoke else "MISSED") if expected else ("silent" if not spoke else "FALSE LINE")
            rows.append((m["id"], m["kind"], m["says"], verdict, lines))
            shutil.rmtree(head, ignore_errors=True)
    finally:
        shutil.rmtree(work, ignore_errors=True)

    md = ["| Mutation | Kind | What it does | skimit | What skimit said |", "|---|---|---|---|---|"]
    for mid, kind, says, verdict, lines in rows:
        said = "<br>".join(l.replace("|", "\\|") for l in lines) if lines else "No change in behaviour."
        md.append(f"| `{mid}` | {kind} | {says} | **{verdict}** | {said} |")
    behaviour = [r for r in rows if r[1] == "behaviour"]
    same = [r for r in rows if r[1] == "same"]
    read = [r for r in rows if r[1] == "read"]
    summary = [
        f"Changes to behaviour said: {sum(r[3] == 'caught' for r in behaviour)} of {len(behaviour)}.",
        f"Changes to code alone kept silent: {sum(r[3] == 'silent' for r in same)} of {len(same)}.",
        f"Read-path changes, silent by design: {sum(r[3] == 'silent' for r in read)} of {len(read)}.",
    ]
    text = "\n".join(summary) + "\n\n" + "\n".join(md) + "\n"
    print(text)
    if "--out" in opts:
        out_file = Path(opts["--out"])
        out_file.parent.mkdir(parents=True, exist_ok=True)
        out_file.write_text(text)


if __name__ == "__main__":
    main()
