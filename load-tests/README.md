# load-tests/

Gatling simulations and committed results (methodology in docs/TESTING.md §6):

- `SteadyStateSimulation` — 1h at 3,000 orders/s
- `SpikeSimulation` — 10× spike for 60 s
- `SoakSimulation` — 8h at 1,500 orders/s

*(simulations to be added; results are committed under `results/<date>/` per run)*
