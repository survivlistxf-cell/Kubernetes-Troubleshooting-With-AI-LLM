# s07 | mode=none | run=1 | 14.2s | needs_search=0

**Q:** My pod 's07-missing-cm' in namespace 'kubexplain-eval' fails to start its container. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** referenced ConfigMap does not exist; create it or fix the reference

---

## Most Likely Reasons the pod **`s07‑missing‑cm`** can’t start (ordered by probability)

| # | Likely cause | Why it’s the top suspect | How to verify (when you have cluster access) | How to fix |
|---|--------------|--------------------------|---------------------------------------------|------------|
| 1 | **Referenced ConfigMap does not exist** (or has a wrong name/namespace) | The pod name itself hints at a missing ConfigMap; a missing ConfigMap is a *hard* error that prevents the container from being created. | ```bash\nkubectl describe pod s07-missing-cm -n kubexplain-eval\n``` <br>Look for events such as `Error: configmap "…" not found`. <br>Also inspect the pod spec: <br>```bash\nkubectl get pod s07-missing-cm -n kubexplain-eval -o yaml | grep -i configMap -A2\n``` | *Create* the ConfigMap with the expected name/namespace, or *update* the pod spec to reference the correct ConfigMap. Example: <br>```bash\nkubectl create configmap my‑cm --from-literal=key=value -n kubexplain-eval\n``` <br>or edit the deployment/Pod: <br>```bash\nkubectl edit pod s07-missing-cm -n kubexplain-eval\n``` |
| 2 | **Referenced Secret is missing or malformed** | Pods often mount both ConfigMaps **and** Secrets; a missing Secret produces the same “cannot create container” error. | ```bash\nkubectl describe pod s07-missing-cm -n kubexplain-eval | grep -i secret -A2\n``` <br>Check events for `secret "…" not found`. | Create the Secret or correct its name. Example: <br>```bash\nkubectl create secret generic my‑secret --from-literal=password=xyz -n kubexplain-eval\n``` |
| 3 | **Image pull failure** (wrong tag, private registry auth, rate‑limit) | If the container image cannot be pulled, the pod stays in `ImagePullBackOff` and never reaches the “Running” state. | ```bash\nkubectl describe pod s07-missing-cm -n kubexplain-eval | grep -i Pull\n``` <br>Events like `Failed to pull image` or `Back-off pulling image` indicate this. | *Public image*: verify tag/name, try pulling locally (`docker pull …`). <br>*Private registry*: ensure a valid `imagePullSecret` exists and is referenced. <br>Update the pod spec with the correct image or secret. |
| 4 | **Insufficient resources (CPU/Memory) on the node** | If the scheduler places the pod on a node that cannot satisfy its `requests`, the pod will stay in `Pending` → `FailedScheduling`. When the pod is already bound, the kubelet may kill it with `OOMKilled` or `Insufficient memory`. | ```bash\nkubectl describe pod s07-missing-cm -n kubexplain-eval | grep -i -E 'Insufficient|OOM'\n``` | Reduce the pod’s resource requests/limits, or add more capacity (scale the node pool, enable cluster autoscaler). |
| 5 | **Node selector / taints & tolerations mismatch** | A pod that specifies a node selector or requires a toleration may be scheduled onto a node that does not match, causing it to stay `Pending`. | ```bash\nkubectl describe pod s07-missing-cm -n kubexplain-eval | grep -i -E 'nodeSelector|taint|toleration'\n``` | Adjust the pod’s `nodeSelector`/`affinity` or add the needed tolerations, or remove the taint from the node if appropriate. |
| 6 | **Init container failure** | If an init container exits with a non‑zero code, the main container never starts. | ```bash\nkubectl describe pod s07-missing-cm -n kubexplain-eval | grep -i Init\n``` | Fix the init container’s command, image, or its own ConfigMap/Secret dependencies. |
| 7 | **PodSecurityPolicy / Admission controller rejection** | Policies that block privileged escalation, hostPath mounts, etc., can cause the pod to be rejected at creation time. | ```bash\nkubectl describe pod s07-missing-cm -n kubexplain-eval | grep -i 'policy'\n``` | Modify the pod spec to comply with the active PSP/OPA policies, or adjust the policy to allow the required capabilities. |
| 8 | **Volume mount errors (wrong path, readOnly conflict, missing PV/PVC)** | A missing PersistentVolumeClaim or a mis‑configured hostPath can stop container creation. | ```bash\nkubectl describe pod s07-missing-cm -n kubexplain-eval | grep -i volume\n``` | Ensure the referenced PVC exists and is bound, or correct the hostPath/emptyDir definition. |
| 9 | **Container command / entrypoint error** (e.g., typo, missing binary) | Even if the pod starts, a bad command makes the container exit immediately, leading to a `CrashLoopBackOff`. | ```bash\nkubectl logs s07-missing-cm -n kubexplain-eval --previous\n``` | Fix the `command`/`args` fields, or ensure the binary exists in the image. |
|10| **Image corruption / incompatible architecture** | Pulling an image built for a different CPU architecture (e.g., amd64 vs arm64) results in immediate container failure. | ```bash\nkubectl describe pod s07-missing-cm -n kubexplain-eval | grep -i 'failed to create container'\n``` | Re‑build/push an image for the node’s architecture, or use a multi‑arch image. |

---

## General verification workflow (run when you regain access)

1. **Inspect the pod’s status and events**  
   ```bash
   kubectl get pod s07-missing-cm -n kubexplain-eval -o wide
   kubectl describe pod s07-missing-cm -n kubexplain-eval
   ```

2. **Check the pod’s full manifest for ConfigMap/Secret references**  
   ```bash
   kubectl get pod s07-missing-cm -n kubexplain-eval -o yaml
   ```

3. **Validate the existence of referenced resources**  
   ```bash
   # ConfigMaps
   kubectl get configmap -n kubexplain-eval
   # Secrets
   kubectl get secret -n kubexplain-eval
   # PVCs (if any)
   kubectl get pvc -n kubexplain-eval
   ```

4. **Look at container logs (if the container started at least once)**  
   ```bash
   kubectl logs s07-missing-cm -n kubexplain-eval
   # If it’s crashing repeatedly:
   kubectl logs s07-missing-cm -n kubexplain-eval --previous
   ```

5. **If the pod is stuck in `Pending`**, examine scheduler decisions:  
   ```bash
   kubectl get pod s07-missing-cm -n kubexplain-eval -o jsonpath='{.status.conditions}'
   ```

6. **If the pod is in `ImagePullBackOff`**, test the image locally or verify pull secrets:  
   ```bash
   docker pull <image>
   kubectl get secret -n kubexplain-eval
   ```

---

### Quick “first‑fix” checklist

1. **Create / correct the ConfigMap** (most probable).  
2. **Create / correct any missing Secret**.  
3. **Confirm the image name/tag and pull secret**.  
4. **Verify resource requests fit the cluster**.  
5. **Check node selector / taints**.  

Address the items in that order; after each change, delete the failing pod so the controller (Deployment, ReplicaSet, etc.) recreates it:

```bash
kubectl delete pod s07-missing-cm -n kubexplain-eval
```

The new pod will reflect the corrected configuration and should transition to `Running` if the root cause has been resolved.
