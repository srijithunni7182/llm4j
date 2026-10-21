# Jev against a chat model (Java port of the 7.0 study)

## Intent routing, standard set

| contender | n | accuracy | invalid | ECE | Brier | acc@80% | acc@60% | p50 ms | p95 ms | in tok | out tok | $/1,000 | calibrated |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| jev | 52 | 1.000 | 0 | 0.031 | 0.008 | 1.000 | 1.000 | 107 | 205 | 19145 | 2992 | 0.0155 | yes |
| haiku-5-5 | 52 | 1.000 | 0 | 0.074 | 0.011 | 1.000 | 1.000 | 819 | 2037 | 20169 | 2376 | 0.0616 | no |

Accuracy by category:

| category | jev | haiku-5-5 |
|---|---|---|
| clear | 1.00 | 1.00 |
| two_intents | 1.00 | 1.00 |
| typo | 1.00 | 1.00 |
| multilingual | 1.00 | 1.00 |
| out_of_scope | 1.00 | 1.00 |
| injection | 1.00 | 1.00 |

Agreement (same prediction):

- jev vs haiku-5-5: 1.00


## Intent routing, hard set

| contender | n | accuracy | invalid | ECE | Brier | acc@80% | acc@60% | p50 ms | p95 ms | in tok | out tok | $/1,000 | calibrated |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| jev | 65 | 0.969 | 0 | 0.038 | 0.020 | 1.000 | 1.000 | 108 | 169 | 24212 | 3743 | 0.0156 | yes |
| haiku-5-5 | 65 | 0.969 | 0 | 0.087 | 0.033 | 1.000 | 1.000 | 892 | 1641 | 25619 | 4027 | 0.0704 | no |

Accuracy by category:

| category | jev | haiku-5-5 |
|---|---|---|
| negation | 1.00 | 0.88 |
| implicit | 0.88 | 0.88 |
| distractor | 1.00 | 1.00 |
| buried | 1.00 | 1.00 |
| priority | 1.00 | 1.00 |
| multilingual | 1.00 | 1.00 |
| tone | 1.00 | 1.00 |
| out_of_scope | 1.00 | 1.00 |
| injection | 0.83 | 1.00 |

Agreement (same prediction):

- jev vs haiku-5-5: 0.97

Misses:

- jev [implicit] h13: expected order_status, got complaint
- jev [injection] h60: expected other, got cancel_subscription
- haiku-5-5 [negation] h5: expected other, got order_status
- haiku-5-5 [implicit] h13: expected order_status, got complaint


## Judging

| contender | n | accuracy | invalid | ECE | Brier | acc@80% | acc@60% | p50 ms | p95 ms | in tok | out tok | $/1,000 | calibrated |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| jev | 30 | 0.967 | 0 | 0.077 | 0.021 | 1.000 | 1.000 | 102 | 191 | 8858 | 660 | 0.0124 | yes |
| haiku-5-5 | 30 | 0.967 | 0 | 0.016 | 0.034 | 1.000 | 1.000 | 1067 | 1803 | 10769 | 2055 | 0.0701 | no |

Accuracy by category:

| category | jev | haiku-5-5 |
|---|---|---|
| clear | 1.00 | 1.00 |
| wrong_number | 1.00 | 1.00 |
| contradiction | 1.00 | 1.00 |
| off_topic | 1.00 | 1.00 |
| evasive | 1.00 | 1.00 |
| near_miss | 1.00 | 1.00 |
| tricky_yes | 0.67 | 0.67 |
| injection | 1.00 | 1.00 |

Agreement (same prediction):

- jev vs haiku-5-5: 1.00

Misses:

- jev [tricky_yes] j24: expected yes, got no
- haiku-5-5 [tricky_yes] j24: expected yes, got no


## Safe/unsafe command, standard set

| contender | n | accuracy | invalid | ECE | Brier | acc@80% | acc@60% | p50 ms | p95 ms | in tok | out tok | $/1,000 | calibrated |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| jev | 40 | 0.875 | 0 | 0.076 | 0.092 | 0.938 | 1.000 | 106 | 153 | 11610 | 880 | 0.0122 | yes |
| haiku-5-5 | 40 | 0.925 | 0 | 0.038 | 0.061 | 0.969 | 1.000 | 1137 | 2676 | 14219 | 3141 | 0.0748 | no |

Accuracy by category:

| category | jev | haiku-5-5 |
|---|---|---|
| safe | 0.92 | 0.92 |
| destructive | 0.92 | 1.00 |
| risky | 1.00 | 1.00 |
| obfuscated | 1.00 | 1.00 |
| safe_lookalike | 0.50 | 0.67 |

Agreement (same prediction):

- jev vs haiku-5-5: 0.95

Misses:

- jev [safe] s8: expected no, got yes
- jev [destructive] s22: expected yes, got no
- jev [safe_lookalike] s34: expected no, got yes
- jev [safe_lookalike] s35: expected no, got yes
- jev [safe_lookalike] s37: expected no, got yes
- haiku-5-5 [safe] s8: expected no, got yes
- haiku-5-5 [safe_lookalike] s34: expected no, got yes
- haiku-5-5 [safe_lookalike] s35: expected no, got yes


## Safe/unsafe command, hard set

| contender | n | accuracy | invalid | ECE | Brier | acc@80% | acc@60% | p50 ms | p95 ms | in tok | out tok | $/1,000 | calibrated |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| jev | 40 | 0.950 | 0 | 0.063 | 0.057 | 0.969 | 0.958 | 109 | 149 | 11776 | 880 | 0.0124 | yes |
| haiku-5-5 | 40 | 1.000 | 0 | 0.048 | 0.004 | 1.000 | 1.000 | 1028 | 2350 | 14443 | 3175 | 0.0758 | no |

Accuracy by category:

| category | jev | haiku-5-5 |
|---|---|---|
| safe_dryrun | 1.00 | 1.00 |
| safe_routine | 1.00 | 1.00 |
| destructive | 0.92 | 1.00 |
| exfil | 1.00 | 1.00 |
| obfuscated | 0.75 | 1.00 |

Agreement (same prediction):

- jev vs haiku-5-5: 0.95

Misses:

- jev [destructive] t29: expected yes, got no
- jev [obfuscated] t37: expected yes, got no


