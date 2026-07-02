# s02 | mode=none | run=1 | 4.8s | needs_search=0

**Q:** My pod 's02-imagepull' in namespace 'kubexplain-eval' will not start. What is the root cause and how do I fix it?

**Expected:** image cannot be pulled (bad name/tag or missing credentials); fix image reference

---

## Root cause  

The pod is stuck in **`ErrImagePull / ImagePullBackOff`** because the container image it is trying to start does not exist:

```
nginx:tag-inexistent-xyz-123
```

The kubelet reports:

```
failed to resolve reference "docker.io/library/nginx:tag-inexistent-xyz-123": not found
```

Kubernetes can only pull an image that is reachable in a registry and that has the exact tag (or digest) you specify. Since the tag `tag-inexistent-xyz-123` is not present in Docker Hub’s `library/nginx` repository, the pull fails, the pod stays in **Pending**, and the kubelet repeatedly backs‑off.

## How to fix it  

### 1. Use a valid image reference  

- **Correct the tag** to an existing one (e.g. `nginx:latest` or `nginx:1.27.0`).  
- If you intended to use a private image, make sure the image name and tag are correct and that the image has been pushed to the registry.

```yaml
containers:
- name: app
  image: nginx:latest   # <-- replace with a real tag
```

### 2. (Optional) Pull policy  

If you want the pod to fall back to a locally cached image when the tag is missing, set an explicit pull policy:

```yaml
imagePullPolicy: IfNotPresent
```

*Note:* `IfNotPresent` will still fail the first time the image is not cached locally, so the primary fix is to use a valid tag.

### 3. Verify registry access (only needed for private registries)  

- Create a `Secret` of type `docker-registry` with the correct credentials.  
- Reference the secret in the pod’s `imagePullSecrets` field.

```yaml
imagePullSecrets:
- name: my-registry-secret
```

### 4. Apply the corrected manifest  

```bash
kubectl apply -f corrected-pod.yaml
```

or, if you are editing an existing pod (e.g., via `kubectl edit`):

```bash
kubectl edit pod s02-imagepull -n kubexplain-eval
```

After the manifest is updated, the kubelet will pull the valid image and the pod should transition to **Running**.

---

**Summary:** The pod fails because the specified image tag does not exist in Docker Hub. Update the pod spec to reference a real image (or push the intended image to the registry) and redeploy. Once the image can be pulled successfully, the pod will start normally.
