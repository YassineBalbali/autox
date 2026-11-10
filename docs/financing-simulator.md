# The financing simulator

"Simuler un financement", on a listing page (`features/listing/financing-simulator.ts`).

## Where

On a listing **for sale** only. Not on a leasing offer, which states its own monthly payment and
whose price is not what anyone pays for the car (`docs/leasing.md`), and not on a damaged car, which
is not what a car loan is quoted for. Both are the listing page's `financeable` rule.

## What it does

The visitor gives a down payment, a duration (12 to 84 months, by year) and an annual rate. The rate
starts at an **example** (`EXAMPLE_RATE_PERCENT`, 5,9 %), labelled on the page as an example and not
an offer, for them to replace with the one they are quoted. It shows the amount borrowed, the
monthly payment, the total repaid and the cost of the credit.

**Computed in the browser, sent nowhere.** No request, no lead form, no contact details: nothing
typed is stored or transmitted.

## The formula

One pure function, `simulateCredit` in `shared/listing/financing.ts`, amounts in cents:

- monthly rate r = annual rate / 12; payment = P·r / (1 − (1 + r)^−n); at 0 %, P / n;
- the payment is rounded to the cent for display; the total is the exact payment × n, rounded
  once, so at 0 % it is exactly the amount borrowed and the cost is zero, instead of a few cents
  off from rounding each payment first (a lender settles those cents on the last payment);
- cost of the credit = total repaid − amount borrowed;
- no figure for nothing borrowed, a negative amount or rate, a rate above 30 %, or a duration
  outside 12–84 or not a whole number of months. The page then says what to correct.

Checked against published loan tables: 10 000 € at 5 % over 48 months is 230,29 € a month and
1 054,06 € of interest.

## The mentions

Always shown with the figures:

- the mandatory warning for consumer credit (Code de la consommation, art. L. 312-5): « Un crédit
  vous engage et doit être remboursé. Vérifiez vos capacités de remboursement avant de vous
  engager. »
- that this is an indicative simulation, not a credit offer, and leaves out insurance and fees.

## What it is not

Not an offer, a TAEG, or an assessment of anyone's means. AutoX is not a credit intermediary; the
simulation states no lender, collects nothing and leads to no application. A real offer would need
the TAEG, the total cost with insurance and the lender's details, all of which a lender provides.
