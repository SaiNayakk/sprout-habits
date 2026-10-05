# sprout-habits

Sprout rewards **investing regularly, never trading often**: "Grow the habit, not the hype."

Everything is worked out from what the customer actually did (their executions, read from the
[order service](https://github.com/SaiNayakk/sprout-oms)) each time it's asked for, so the same history
always gives the same picture and nothing can drift:

- **Streak**: consecutive months with a delivery purchase. Selling doesn't break it, intraday trading doesn't count. Every 6 months earns a **freeze** (at most 2), used automatically on a missed month. **At risk** after the 20th with no purchase yet.
- **Levels** (Seedling, Sapling, Young tree, Grove, Forest) by months invested, and **badges** for milestones.
- **Points** for each month invested and each plan instalment, which **vest after 30 days** if the shares aren't sold; sold sooner, they're forfeited.
- **A nudge, not a ban**: lots of intraday or selling lately shows a cool-off message.
- **Squads**: private groups joined by invite code, **ranked by the habit, never by money**. Members can choose to show a range for how much they've invested, never the amount.
- **Readiness** advice before a first investment, and **Future You**: what a monthly amount could grow to.

Only squads, privacy choices and readiness answers are stored.

## Part of Sprout

[Sprout](https://sainayakk.github.io/sprout-platform/) is a simulated brokerage built from scratch as
separate services, each with its own repository and contract. Architecture, environments and test
evidence live in [sprout-platform](https://github.com/SaiNayakk/sprout-platform); this service's API is
[`habits-v1.yaml`](https://github.com/SaiNayakk/sprout-contracts/blob/main/src/main/resources/sprout/contracts/openapi/habits-v1.yaml)
in sprout-contracts. It runs inside the **trading** host.

`./mvnw verify` runs the tests: the habit rules on hand-checked histories, and the API on a real
Postgres against stand-ins for accounts, market data and trading history.

## License

MIT
