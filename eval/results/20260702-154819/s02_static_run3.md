# s02 | mode=static | run=3 | 6.8s | needs_search=0

**Q:** My pod 's02-imagepull' in namespace 'kubexplain-eval' will not start. What is the root cause and how do I fix it?

**Expected:** image cannot be pulled (bad name/tag or missing credentials); fix image reference

---

### What is happening?

| Observation | Explanation |
|-------------|-------------|
| **Pod status**: `Pending` → container state **`ImagePullBackOff`** | The kubelet tried to pull the container image but failed. |
| **Event**: `Failed to pull image "nginx:tag-inexistent-xyz-123": … not found` | The image reference `nginx:tag-inexistent-xyz-123` does **not exist** in the public Docker Hub registry (`docker.io/library/nginx`). |
| **Event**: `Back-off pulling image …` | After the first failure the kubelet backs off and retries, ending in the `ImagePullBackOff` condition. |

**Root cause:** The pod is asking for a non‑existent image tag. Because the tag cannot be resolved, the kubelet cannot download the image, so the container never starts.

---

### How to fix it

1. **Verify the correct image tag**  
   - Browse the Docker Hub page for the *nginx* image (https://hub.docker.com/_/nginx) or run a local `docker pull` to see which tags are available.  
   - Choose a tag that actually exists (e.g. `nginx:1.25`, `nginx:latest`, or any other valid tag).

2. **Update the pod (or Deployment, StatefulSet, etc.) to use a valid image**  
   - If the pod is created directly, edit the manifest and replace the bad tag:  

```yaml
containers:
- name: app
  image: nginx:latest   # <-- replace with a valid tag
```

   - If the pod is managed by a higher‑level controller (Deployment, ReplicaSet, etc.), edit that controller instead:

```bash
kubectl edit deployment <name> -n kubexplain-eval
# change .spec.template.spec.containers[0].image to a valid tag
```

   - Apply the corrected manifest:

```bash
kubectl apply -f <corrected‑manifest>.yaml
```

3. **(Optional) Test the image locally**  
   - Run a quick pull on any machine that has Docker or `crictl` to confirm the tag works:

```bash
docker pull nginx:latest
# or
crictl pull docker.io/library/nginx:latest
```

4. **Verify the pod starts**  

```bash
kubectl get pod s02-imagepull -n kubexplain-eval -w
```

   - The pod should transition to `Running` once the image is successfully pulled.

---

### Common pitfalls to watch for

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| `ImagePullBackOff` but the tag **does** exist | Registry requires authentication (private repo) | Create an `imagePullSecret` and reference it in the pod spec (`imagePullSecrets`). |
| `ImagePullBackOff` after fixing the tag | Node cannot reach the internet (network/DNS issue) | Check node network connectivity (`ping hub.docker.com`) and DNS resolution. |
| Repeated failures after fixing the tag | Old pod still cached the failed image | Delete the pod so a fresh one is created: `kubectl delete pod s02-imagepull -n kubexplain-eval`. |

---

### TL;DR

*Root cause*: The pod requests `nginx:tag-inexistent-xyz-123`, a tag that does not exist on Docker Hub, leading to `ImagePullBackOff`.  
*Fix*: Change the pod (or its controller) to reference a **valid image tag** (e.g., `nginx:latest`), apply the change, and let the pod restart. If the image is private, add the appropriate `imagePullSecret`. Once the image can be pulled, the pod will start normally.
