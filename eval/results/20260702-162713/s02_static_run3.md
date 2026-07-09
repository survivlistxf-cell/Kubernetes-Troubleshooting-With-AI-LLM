# s02 | mode=static | run=3 | 14.7s | needs_search=0

**Q:** My pod 's02-imagepull' in namespace 'kubexplain-eval' will not start. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** image cannot be pulled (bad name/tag or missing credentials); fix image reference

---

## Most likely reasons why **`s02-imagepull`** cannot start (in order of probability)

| # | Likely cause | Why it is the most common culprit | How to verify (when you regain access) | How to fix |
|---|--------------|-----------------------------------|----------------------------------------|------------|
| 1 | **Image‑pull failure (image not found or tag typo)** | The pod name itself (`s02-imagepull`) hints that the problem is the container image. A miss‑spelled repository name, missing tag, or a tag that was deleted from the registry will immediately put the pod into `ImagePullBackOff` / `ErrImagePull`. | • Look at `kubectl describe pod s02-imagepull -n kubexplain-eval` → check the **Events** section for messages like `Failed to pull image "<repo>/<image>:<tag>"` or `manifest for <image> not found`. <br>• Run `kubectl get pod s02-imagepull -n kubexplain-eval -o yaml` and inspect the `spec.containers[].image` field. | • Correct the image name or tag in the pod/deployment manifest and re‑apply. <br>• If you are using a mutable tag (e.g., `latest`), make sure the tag still points to a valid image or pin to a digest. |
| 2 | **Missing or invalid image‑pull secret** (private registry) | When pulling from a private registry the kubelet needs a secret that contains the registry credentials. If the secret is missing, expired, or the credentials are wrong the kubelet will repeatedly fail to authenticate. | • In the pod description, check the `imagePullSecrets` field. <br>• Look at the events for `Failed to pull image ...: unauthorized: authentication required`. <br>• Verify the secret with `kubectl get secret <secret-name> -n kubexplain-eval -o yaml` (the `dockerconfigjson` data should be present). | • Re‑create or update the secret with correct credentials: `kubectl create secret docker-registry … --dry-run=client -o yaml | kubectl apply -f -`. <br>• Ensure the pod/deployment references the secret name in `imagePullSecrets`. |
| 3 | **Network/DNS connectivity to the registry** | Even with a correct image and valid credentials, the node must be able to reach the registry (e.g., Docker Hub, ECR, GCR). A blocked outbound firewall, broken DNS, or a mis‑configured proxy will cause pull failures. | • In the pod events you may see `Failed to resolve ...` or `dial tcp ...: i/o timeout`. <br>• From a node (or a debug pod) run a simple `curl`/`wget` to the registry endpoint to test connectivity. <br>• Check any `NetworkPolicy` that might be denying egress from the namespace. | • Fix the underlying network issue: open the required outbound ports (443/80), correct DNS settings, or add a proper proxy configuration. <br>• If a `NetworkPolicy` is too restrictive, add an egress rule that allows traffic to the registry’s domain/IP. |
| 4 | **Registry rate‑limiting or quota exceeded** | Public registries (Docker Hub, Quay, etc.) enforce pull‑rate limits per IP or per account. When the limit is hit the kubelet receives a `429 Too Many Requests` error and backs off. | • Events will contain `pull rate limit exceeded` or `429`. <br>• Check the registry’s dashboard (if you have access) for rate‑limit statistics. | • Authenticate to the registry (create an image‑pull secret) to get a higher quota. <br>• Cache the image in a private registry within the cluster (e.g., Harbor) and pull from there. |
| 5 | **Insufficient node resources (disk, image‑layer storage)** | The node’s container runtime needs space to store the image layers. If the node’s root or `/var/lib/containerd` (or Docker) partition is full, the pull will fail with `no space left on device`. | • Node description (`kubectl describe node <node>`) will show `DiskPressure` condition. <br>• Pod events may show `Failed to pull image ...: no space left on device`. | • Clean up unused images on the node (`crictl images`, `docker image prune`). <br>• Increase node disk size or add more nodes to the cluster. |
| 6 | **Node is NotReady / kubelet not running** | If the node that the pod is scheduled onto is unhealthy, the kubelet cannot perform the pull. The pod will stay in `Pending` or move to `ImagePullBackOff` after a failed attempt. | • `kubectl get nodes` will show the node’s `STATUS` as `NotReady`. <br>• `kubectl describe node <node>` will contain recent `Kubelet` or `Docker` errors. | • Restart the kubelet service on the node, or reboot the node. <br>• If the node is permanently unhealthy, cordon and drain it, then let the scheduler place the pod on a healthy node. |
| 7 | **Pod spec mis‑configuration (wrong `imagePullPolicy`)** | An explicit `imagePullPolicy: Always` on a tag that does not exist will force a pull on every restart, causing repeated failures. | • Check the pod spec for `imagePullPolicy`. <br>• If it is set to `Always` and the tag is missing, the pod will never start. | • Change the policy to `IfNotPresent` (or remove it to let Kubernetes infer) and re‑apply the manifest. |
| 8 | **Security policies blocking the pull (PodSecurityPolicy, Seccomp, AppArmor)** | Some clusters enforce policies that prevent pulling images from untrusted registries or that require specific annotations. | • Events may contain `failed to create containerd task: permission denied`. <br>• Review any `PodSecurityPolicy` or `AdmissionWebhook` logs. | • Adjust the policy to allow the registry, or add the required annotations to the pod spec. |

---

### General verification checklist (to run once you can access the cluster)

1. **Pod status & events** – `kubectl describe pod s02-imagepull -n kubexplain-eval`.  
   Look for `ErrImagePull`, `ImagePullBackOff`, `Failed to pull image`, or any `Unauthorized`, `NotFound`, `Timeout`, `429` messages.

2. **Image definition** – `kubectl get pod s02-imagepull -n kubexplain-eval -o yaml`.  
   Confirm the exact `image:` string and any `imagePullPolicy:` or `imagePullSecrets:` entries.

3. **Node health** – `kubectl get node` and `kubectl describe node <node>` for the node the pod is scheduled on.  
   Check for `Ready` status, `DiskPressure`, or `NetworkUnavailable`.

4. **Secret validity** – `kubectl get secret <secret-name> -n kubexplain-eval -o yaml`.  
   Verify the `dockerconfigjson` data is present and not expired.

5. **Network reachability** – launch a temporary debug pod (e.g., `busybox` or `curlimages/curl`) in the same namespace and try to `curl` the registry endpoint.  
   This isolates DNS/proxy issues from the node’s runtime.

6. **Registry side** – if you have access to the registry’s UI or API, confirm the image/tag exists and that your account has not hit a rate limit.

7. **Cluster‑wide policies** – review any `NetworkPolicy`, `PodSecurityPolicy`, `AdmissionWebhook`, or `ImagePolicyWebhook` that could reject the pull.

---

### Quick “first‑fix” workflow (when you regain access)

1. **Read the pod events** – they almost always tell you the exact reason (e.g., *unauthorized*, *manifest not found*, *i/o timeout*).  
2. **If the error is “manifest not found”** → fix the image name/tag.  
3. **If the error is “unauthorized”** → check or recreate the image‑pull secret.  
4. **If the error is a timeout or DNS failure** → test connectivity from a debug pod; fix network or DNS.  
5. **If the error is a rate‑limit** → add authentication or pull from a private cache.  
6. **If the node is unhealthy** → remediate the node (restart kubelet, free disk, or move the pod).  

Following this ordered list should let you pinpoint the root cause quickly once you can run commands again. Good luck!
