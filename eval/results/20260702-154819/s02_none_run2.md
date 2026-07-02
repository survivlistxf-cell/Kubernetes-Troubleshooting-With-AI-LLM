# s02 | mode=none | run=2 | 6.3s | needs_search=0

**Q:** My pod 's02-imagepull' in namespace 'kubexplain-eval' will not start. What is the root cause and how do I fix it?

**Expected:** image cannot be pulled (bad name/tag or missing credentials); fix image reference

---

## Root cause  

The pod is stuck in **`ErrImagePull / ImagePullBackOff`** because the container image it is trying to start does not exist in the registry:

```
nginx:tag-inexistent-xyz-123
```

The kubelet reports:

```
failed to resolve reference "docker.io/library/nginx:tag-inexistent-xyz-123": not found
```

Kubernetes therefore cannot download the image, marks the container state as **`Waiting`** with reason **`ErrImagePull`**, and then backs off, resulting in the pod remaining in **`Pending`**.

## How to fix it  

### 1. Use a valid image tag  

Replace the non‑existent tag with a tag that actually exists in Docker Hub (or in your private registry).

```bash
# Example: change to the latest official tag
kubectl set image pod/s02-imagepull app=nginx:latest -n kubexplain-eval
```

If you are applying a manifest file, edit the `image:` field:

```yaml
containers:
- name: app
  image: nginx:latest   # <-- use a real tag
```

Then re‑apply:

```bash
kubectl apply -f <your‑manifest>.yaml
```

### 2. Push the intended image (if you really need that tag)

If the tag `tag-inexistent-xyz-123` is supposed to exist (e.g., it is a custom build), push it to the registry first:

```bash
# Build locally
docker build -t nginx:tag-inexistent-xyz-123 .

# Tag for Docker Hub (or your private registry)
docker tag nginx:tag-inexistent-xyz-123 docker.io/library/nginx:tag-inexistent-xyz-123

# Push
docker push docker.io/library/nginx:tag-inexistent-xyz-123
```

After the image is available, the pod will be able to pull it.

### 3. Verify the image can be pulled manually (optional)

```bash
# From any node (or your workstation) that uses the same network
docker pull nginx:tag-inexistent-xyz-123
```

If this command fails with the same “not found” error, the image truly does not exist in the registry.

### 4. Check imagePullPolicy (only relevant if the image already exists locally)

If you have the correct image cached on the node and want Kubernetes to use the local copy, set:

```yaml
imagePullPolicy: IfNotPresent
```

or `Never` if you never want a pull. This avoids the pull attempt altogether, but only works when the image is already present on the node.

```bash
kubectl patch pod s02-imagepull -n kubexplain-eval \
  -p '{"spec":{"containers":[{"name":"app","imagePullPolicy":"IfNotPresent"}]}}'
```

### 5. Clean up the back‑off state (optional)

After fixing the image reference, you may need to delete the pod so that a fresh pod is created with the corrected spec:

```bash
kubectl delete pod s02-imagepull -n kubexplain-eval
```

Kubernetes will recreate it (if it is managed by a controller) or you can re‑apply the manifest.

---

**Summary:** The pod cannot start because the image tag `nginx:tag-inexistent-xyz-123` does not exist in Docker Hub. Update the pod spec to reference a valid image tag, or push the intended image to the registry, then recreate or patch the pod. Once the image is resolvable, the pod will transition to `Running`.
