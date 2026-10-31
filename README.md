# jenkins-atom-k8s-deploy

用 `kubectl` 部署：改镜像、apply/delete 清单，并**等 rollout 完成**。

真正让人踩坑的不是 `kubectl set image` 那一下，而是 Pod 起不来——所以 `waitForRollout` 默认开着，`rollout status` 超时算**失败**，不会当成绿构建。kubeconfig 可以给路径，也可以直接贴内容（写进 0600 临时文件，跑完删除）。

错误分类：连不上集群 = `ENVIRONMENT`，清单/manifest 被拒 = `USER`，其余 = `THIRD_PART`。

```bash
./build.sh && ./tools/smoke.sh   # kubectl 是桩，断言的是 argv 与判定
```
