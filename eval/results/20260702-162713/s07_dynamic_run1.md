# s07 | mode=dynamic | run=1 | 14.5s | needs_search=0

**Q:** My pod 's07-missing-cm' in namespace 'kubexplain-eval' fails to start its container. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** referenced ConfigMap does not exist; create it or fix the reference

---

## Most likely reasons the pod **`s07‑missing‑cm`** cannot start (ordered by probability)

| # | Likely cause | Why it is a common failure for a pod that never gets past *Waiting* / *ContainerCreating* | How you can verify it (once you have cluster access) | How to fix it |
|---|--------------|----------------------------------------------------------|------------------------------------------------------|----------------|
| 1 | **Missing or unreadable ConfigMap / Secret** (the name *missing‑cm* is a strong hint) | The pod spec probably contains a `volume` or `envFrom` that references a ConfigMap that does not exist, or the ConfigMap exists but the key you request is absent. When the kubelet cannot mount the volume it puts the container in `Waiting` with reason **`CreateContainerConfigError`**. | • Run `kubectl describe pod s07-missing-cm -n kubexplain-eval` and look at the **Events** section. <br>• Check the **State** of the container – you will see `Waiting` and a **Reason** like `CreateContainerConfigError` or `ConfigMapNotFound`. <br>• List the ConfigMap: `kubectl get cm <name> -n kubexplain-eval` and compare the name/key with what the pod expects. | • Create the missing ConfigMap (or add the missing key) with the exact name referenced in the pod. <br>• If the ConfigMap is intentionally optional, add `optional: true` to the volume/env reference. <br>• Re‑apply the pod (or delete it so the controller recreates it). |
| 2 | **Image pull failure** (wrong image name, missing tag, private registry auth) | If the container image cannot be pulled, the kubelet puts the container in `Waiting` with reason **`ErrImagePull`** or **`ImagePullBackOff`**. This is the second‑most frequent start‑up blocker. | • In `kubectl describe pod …` look for events such as `Failed to pull image` or `Back-off pulling image`. <br>• Check the `Image:` field in the pod spec and verify it matches a repository you can reach from the node. | • Correct the image name/tag. <br>• If the image lives in a private registry, create a proper `imagePullSecret` and reference it in the pod (or add it to the service account). <br>• Ensure the node has network connectivity to the registry. |
| 3 | **Insufficient resources (CPU / memory) on any node** | When the scheduler cannot find a node that satisfies the pod’s resource requests, the pod stays in **Pending** with a `FailedScheduling` event. If the pod is already bound to a node but the kubelet cannot start the container because of over‑commit, you will see `Insufficient memory` or `Insufficient CPU` in the container state. | • `kubectl describe pod …` → look for `FailedScheduling` events or `Insufficient cpu/memory`. <br>• Run `kubectl get nodes -o wide` and check the allocatable resources vs. the pod’s requests. | • Reduce the pod’s `resources.requests`/`limits`. <br>• Add more node capacity (scale the cluster) or enable cluster‑autoscaler. |
| 4 | **Node selector / taints & tolerations mismatch** | If the pod specifies a `nodeSelector`, `nodeAffinity`, or requires a toleration that no node provides, the scheduler will keep the pod in **Pending** with a `FailedScheduling` reason. | • In the pod description, examine `Node-Selectors`, `Affinity`, and `Tolerations`. <br>• Check node labels and taints (`kubectl get nodes -L <label>` and `kubectl describe node <node>`). | • Adjust the pod’s selector/affinity to match an existing node, or add the required label/taint to a node. <br>• Add a matching toleration if the node is tainted. |
| 5 | **Pod security policy / admission webhook rejection** | A restrictive PSP (or a validating webhook) can reject the pod creation, leaving it in `Pending` with a `FailedCreate` event. The pod may appear to be “stuck” because the controller keeps trying to create it. | • Look for events with `FailedCreate` and a message from `admission webhook` or `podsecuritypolicy`. <br>• Check the cluster’s PSPs or webhook configurations that target pods. | • Update the PSP to allow the requested capabilities (e.g., privileged, hostPath). <br>• Modify or disable the offending webhook, or adjust the pod spec to satisfy the policy. |
| 6 | **Finalizer / deletion dead‑lock** (less common for a pod that never started) | If a previous deletion left a finalizer on the pod, the API server may keep the object but the kubelet cannot start a new container. The pod will show `Terminating` rather than `Waiting`, but it can be confused with a start‑up problem. | • `kubectl get pod s07-missing-cm -n kubexplain-eval -o yaml` and inspect the `metadata.finalizers` field. <br>• Look for events mentioning `finalizer` or `deletion`. | • Remove the finalizer manually (`kubectl patch pod … --type=json -p='[{"op":"remove","path":"/metadata/finalizers"}]'`). <br>• Ensure any custom controllers that add finalizers handle pod deletion correctly. |

### How to verify the root cause (once you can run commands)

1. **Describe the pod** – `kubectl describe pod s07-missing-cm -n kubexplain-eval`.  
   The **Events** list and the **State/Reason** of each container give the first clue (e.g., `CreateContainerConfigError`, `ErrImagePull`, `FailedScheduling`).

2. **Inspect the pod spec** – `kubectl get pod s07-missing-cm -n kubexplain-eval -o yaml`.  
   Look for:
   * `volumes` that reference a ConfigMap/Secret (`configMap.name`).
   * `envFrom` or `env` that reference a ConfigMap/Secret.
   * `image:` field.
   * `resources.requests/limits`.
   * `nodeSelector`, `affinity`, `tolerations`.

3. **Check the referenced ConfigMap/Secret** – `kubectl get cm <name> -n kubexplain-eval` (or `secret`).  
   Verify the name matches exactly and that the required keys exist.

4. **Check node resources** – `kubectl get nodes` and `kubectl describe node <node>` to see allocatable CPU/memory and labels/taints.

5. **Review cluster‑wide policies** – `kubectl get psp` (if PSP is enabled) and `kubectl get validatingwebhookconfigurations` / `mutatingwebhookconfigurations` for any pod‑related rules.

### Quick remediation checklist (apply after you have access)

- **If ConfigMap is missing**: `kubectl create configmap <name> --from-literal=key=value -n kubexplain-eval` (or edit the manifest to point to an existing ConfigMap).  
- **If image pull fails**: correct the image reference, add a proper `imagePullSecret`, or ensure the node can reach the registry.  
- **If resources are insufficient**: lower the pod’s requests/limits or add capacity to the cluster.  
- **If node selector/taints block scheduling**: adjust the pod’s selector/affinity or label/taint a node accordingly.  
- **If a policy/webhook blocks creation**: modify the policy to allow the pod’s requirements or change the pod spec to comply.  
- **If a finalizer is stuck**: remove the finalizer manually and investigate the controller that added it.

By following the verification steps above, you should be able to pinpoint which of these common failure modes is responsible for the pod `s07-missing-cm` staying in a non‑running state, and then apply the corresponding fix.
