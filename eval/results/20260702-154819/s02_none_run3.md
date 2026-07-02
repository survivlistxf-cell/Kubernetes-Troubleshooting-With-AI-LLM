# s02 | mode=none | run=3 | 7.4s | needs_search=0

**Q:** My pod 's02-imagepull' in namespace 'kubexplain-eval' will not start. What is the root cause and how do I fix it?

**Expected:** image cannot be pulled (bad name/tag or missing credentials); fix image reference

---

### What is happening?

| Observation | Meaning |
|-------------|---------|
| **State:** `Waiting` – **Reason:** `ErrImagePull` | The kubelet tried to download the container image but failed. |
| **Event:** `Failed to pull image "nginx:tag‑inexistent‑xyz‑123"` – `code = NotFound` | The image reference does not exist in the registry (`docker.io/library/nginx`). |
| **Event:** `Back‑off pulling image …` – `ImagePullBackOff` | After the first failure the kubelet backs off and retries, but the same error repeats. |
| **Pod status:** `Pending` (never reaches `Running`) | Because the container image cannot be obtained, the pod never starts. |

**Root cause:** The pod is asking for an image that does not exist: `nginx:tag‑inexistent‑xyz‑123`. Docker Hub (the default registry) returns *NotFound* for that tag, so the kubelet cannot pull it.

---

### How to fix it

1. **Pick a valid image reference**  
   - Use an existing tag of the `nginx` image (e.g. `nginx:latest`, `nginx:1.27`, etc.).  
   - If you intended to use a private image, make sure the tag exists in the private registry and that the pod has the proper `imagePullSecrets`.

2. **Update the pod (or the controller that created it)**  
   - If the pod was created directly (e.g. via `kubectl run` or a raw `Pod` manifest), delete it and recreate it with the corrected image.  
   - If the pod is managed by a higher‑level controller (Deployment, ReplicaSet, etc.), edit that controller’s spec and let it roll out a new replica.

#### Example commands (adjust names as needed)

```bash
# 1. Delete the broken pod (it will be recreated by its controller, or you can recreate it manually)
kubectl delete pod s02-imagepull -n kubexplain-eval
```

```bash
# 2. If you are using a plain Pod manifest, apply a corrected version:
cat <<EOF > pod-fixed.yaml
apiVersion: v1
kind: Pod
metadata:
  name: s02-imagepull
  namespace: kubexplain-eval
spec:
  containers:
  - name: app
    image: nginx:latest   # <-- change to a valid tag
EOF
kubectl apply -f pod-fixed.yaml
```

```bash
# 3. If the pod is part of a Deployment, edit the Deployment to use a valid image:
kubectl edit deployment <deployment-name> -n kubexplain-eval
# (change .spec.template.spec.containers[0].image to a valid tag, then save)
```

3. **Verify the fix**

```bash
kubectl get pod s02-imagepull -n kubexplain-eval
kubectl describe pod s02-imagepull -n kubexplain-eval
```

You should see the pod transition to `Running` and the `State` of the `app` container become `Running` (or `Terminated` if it exits quickly).

---

### Quick checklist

- [ ] Confirm the image name and tag exist in the registry (`docker pull nginx:<tag>` works locally).  
- [ ] If using a private registry, ensure `imagePullSecrets` are defined and the secret contains correct credentials.  
- [ ] Update the pod (or its controller) with the correct image reference.  
- [ ] Delete/recreate the pod or let the controller roll out the change.  
- [ ] Verify the pod reaches `Running` without `ErrImagePull` / `ImagePullBackOff`.

Once the image reference is valid, the kubelet can pull it, the container starts, and the pod will become healthy.
