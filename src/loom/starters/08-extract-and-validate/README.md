# 08 · Extract, then validate

Free text becomes checked fields. The script applies its own rule (a missing total goes to a person) and stops on purpose when the text cannot be read.

## What it shows

- `output_schema` with several types
- `halt` for stopping on purpose
- `return { ... }` handing back an object

Read more: [05-structured-answers-and-failure.md](../../../../docs/tutorial/05-structured-answers-and-failure.md), [02-data-and-variables.md](../../../../docs/tutorial/02-data-and-variables.md).

## The workflow

```loom
// 08-extract-and-validate: turn free text into checked fields. The agent must answer in a fixed shape (a wrong shape
// is retried once, then the step fails), and the script then applies its own rules to the fields.

substitution: braces

budget { tokens: 80000  calls: 15 }

agent Extractor {
    model: "gemini-2.5-flash"
    system: "Extract the invoice fields from the text. Use 0 when the amount is missing."
    temperature: 0.0
    output_schema: { vendor: string, invoice_number: string, total: number, currency: enum["USD", "EUR", "INR", "OTHER"] }
}

workflow Main(text) {
    delegate "Extract the invoice from:\n{text}" to Extractor -> invoice retry 1 on_failure {
        halt "Could not read an invoice from the text: {_error}"
    }

    alt (invoice.total <= 0) {
        human_prompt "No total could be read for invoice {invoice.invoice_number} from {invoice.vendor}. What is the total?" -> typed_total
        set invoice_total = "{typed_total}"
    } else {
        set invoice_total = "{invoice.total}"
    }

    return { vendor: "{invoice.vendor}", number: "{invoice.invoice_number}", total: "{invoice_total}", currency: "{invoice.currency}" }
}
```

## The shape of it

This picture is drawn by `weave graph` from the file above, not by hand.

<!-- graph:start -->
```mermaid
flowchart TD
  start(["Start"])
  n1["delegate Extractor"]
  n2{{"invoice.total<=0?"}}
  n3[/"ask a person"/]
  n4["set invoice_total"]
  n5["set invoice_total"]
  n6["return"]
  n7["halt"]
  end_(["End"])
  start --> n1
  n1 --> n2
  n2 -->|then| n3
  n3 --> n4
  n2 -->|else| n5
  n4 --> n6
  n5 --> n6
  n6 --> end_
  n1 -->|failure| n7
  n7 --> end_
```
<!-- graph:end -->

## Run it

```bash
cp .env.example .env        # then put your keys in .env (it is git-ignored)
weave check main.loom       # does it load? what is missing?
weave test main.loom --coverage   # scripted models: no key, no network, no cost
weave run main.loom --workflow Main --input text="ACME Ltd invoice INV-7, total 120.50 USD" --max-tokens 50000 --max-calls 20   # a real run, capped
```

## What it needs

- `GEMINI_API_KEY` (any model works: change the `model:` lines)

## Try changing it

- Add a currency rule: ask a person when the currency is OTHER.
- Add a `retry 2` and read how a wrong shape is corrected once.

Back to the [starter set](../README.md).
