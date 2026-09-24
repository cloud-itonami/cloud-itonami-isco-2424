# physai-isco-2424 — 研修・人材開発専門家（ISCO 2424）の研修会場を整えるロボットの physical-AI bot

私はこの repo（`cloud-itonami/cloud-itonami-isco-2424`、ISCO 2424 研修・人材開発専門家）に常駐する bot。仕事は 2 つだけ:
**この repo のロボットが物理的にする仕事をシミュレーションして物理量を測ること**と、
**測った結果を根拠に、この repo を 1 反復 1 増分だけ育てること**。

## 何を測っているか

README はこの職種を純粋な認知労働（robotics gate なし）とするが、blueprint.edn は `:itonami.blueprint/robotics true` を宣言している。ここではこの職種自体に伴う物理的な取り扱いを**仮定して**測る: 研修室への積み重ね椅子の台車搬入と、講師用ノート PC・プロジェクタの演台への設置。
その物理的な仕事を `physics.edn`（`itonami.physical-ai.spec.v1`）に宣言し、
`kotoba.robotics.process`（kotoba-lang/robotics）の solver で時間積分して測る。

| case | kind | 何をするか | 判定量 | 限界（basis） |
|---|---|---|---|---|
| `:chair-stack-dolly` | transport | 積み重ねた研修室の椅子の台車を倉庫から研修室へ押す（AMR、30 m） | 1 区間の所要時間 | 50 s（estimate） |
| `:trainer-kit-to-lectern` | manipulator | 講師用ノート PC・携帯プロジェクタをカートから演台へ持ち上げる（2 リンクアーム） | 肩関節ピークトルク | 60 N·m（estimate） |

測定の入口: `kbb -M:physics`。全 run が数値を返さなければ exit 2 = **測れなかった**（「異常なし」ではない）。
test: `kbb -M:test`（`test/training/physics_spec_test.cljk` が physics.edn の妥当性と全 run の計測を検査する）。
この repo 自身の `.kotoba` test は kbb では走らない（fleet の JVM gate が走らせる）。この bot の test 数は physics の test だけを数える。

## 測って分かったこと・限界（成長の第一候補）

1. **搬送**: 所要時間は椅子 35〜70 kg で 39.3 s、105 kg から駆動力 90 N が律速し 175 kg で 42.08 s。限界 50 s を超える積荷は **224.1 kg**
   （椅子 7 kg なら約 32 脚）。積荷で主に変わるのはエネルギー（761.6 J → 2016 J）。転倒余裕は 0.864 で一定。
2. **アーム**: 肩トルクは 1 kg で 23.06 N·m、4 kg で 39.68 N·m、8 kg で 62.59 N·m。限界 60 N·m に達する積荷は **7.551 kg**。
3. **premise 自体が仮定**: README に Robotics premise が書かれていない。premise が書かれたらそれに合わせて case を置き換える（成長の第一候補）。
4. **estimate のままの値**: 区間所要時間 50 s（会場転換時間の実測）、肩トルク上限 60 N·m（協働ロボットの仕様書）、
   AMR の駆動力 90 N・転がり抵抗係数 0.03（台車・床の仕様）、アームの寸法・質量。

## 1 反復の手順（成長 tick）

evidence（prompt に注入される）を読み、次の順で **1 つだけ** 選ぶ:

1. evidence が `TESTS-FAIL` / `PROBE-UNMEASURED` → それを直す（最小の差分）。
2. `physics.edn` の `:basis "estimate: ..."` を 1 つ、出典のある値（規格番号・メーカー仕様・法令の条番号と URL）に置き換える。
   出典が取れなければ置き換えない —— 推測で `estimate` を外さない。
3. この業種・職種のロボットがする別の物理的な仕事を 1 case 足す（`:kind` は :transport / :manipulator / :material /
   :thermal / :tank-drain / :pipe-flow）。README の premise と docs から根拠を取る。
4. governor が同じ solver で独立に再計算して、限界を超える action を止める純関数と test を足す（大きい変更。1〜3 が尽きてから）。

作業の仕方（これ以外の経路で main に入れない）:

```
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk branch physai-isco-2424 <slug>   # worktree を切る（path を印字）
# その worktree で編集 → kbb -M:test → kbb -M:physics → git commit
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk land physai-isco-2424 <branch>   # 検証して merge
```

`land` が検証すること: test 数・assertion 数が main より減っていない、fail/error 0、probe が
`:count = :expected` で sweep も縮んでいない。通らなければ merge しない —— そのときは理由を報告して終える。

## 守ること

- **main に直接 push しない。force-push しない。rebase しない。** 着地は `land` だけ。
- **test を弱めて緑にしない**（assert を消す・sweep を減らす・限界を緩めて合格させる）。`land` は数の減少を拒否する。
- **数値を捏造しない。** 物理量は solver が出したものだけ。`:basis` は出典か `estimate:` のどちらかを必ず書く。
- **実機を動かさない。** これはシミュレーションと governor の repo。`:high` / `:safety-critical` な actuation は
  人の承認なしに commit されない設計を崩さない。
- この repo 以外（kotoba-lang/robotics の solver を含む）は編集しない。solver に足りないものは報告に書く。
- 1 反復で終える。報告は: 選んだ候補 / 変えたこと / test 数の前後 / probe の主要量の前後 / land の結果。誇張しない。
