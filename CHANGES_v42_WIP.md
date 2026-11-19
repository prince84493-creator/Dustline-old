# v42 (ADHURA - beech me roka gaya)
Game me abhi koi badlav NAHI: houses.bin, Kotlin, imaraten sab v41 jaisi hain.
Badli / nayi generator files (tools/houses/):
- interior.py: naye furniture (shelf, desk, crate, altar, mat...) + loot count.
- reach.py: custom start / bounds.
- bld.py (NAYI): dukaan, dhaba, school, hospital, bank, godown, mandir, masjid, petrol, clock tower ke andar se khule templates (seedhi, darwaze, loot).
  Test: 12 me se 10 templates reach-test pass. Fail: 7x7 teen manzil dukaan (chhat ki seedhi), water tower (deck tak ramp).
Baaki: make_houses_towns.py me inhe lagana, houses.bin instancing format, Kotlin loader + draw culling.
