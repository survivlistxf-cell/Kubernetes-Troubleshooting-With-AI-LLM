# s02 | mode=dynamic | run=2 | 5.0s | needs_search=0

**Q:** My pod 's02-imagepull' in namespace 'kubexplain-eval' will not start. What is the root cause and how do I fix it?

**Expected:** image cannot be pulled (bad name/tag or missing credentials); fix image reference

---

## Root cause  

The pod is stuck in **`ErrImagePull` / `ImagePullBackOff`** because the container image reference does not exist:

```
nginx:tag-inexistent-xyz-123
```

The kubelet tried to pull the image from Docker Hub and received a **`NotFound`** error:

```
failed to resolve reference "docker.io/library/nginx:tag-inexistent-xyz-123": not found
```

Since the tag cannot be resolved, the pod never gets an image to run, so it remains in the **Pending** state.

## How to fix it  

1. **Use a valid image tag**  
   - Choose an existing tag (e.g. `nginx:latest` or a specific version like `nginx:1.27`).  
   - Update the pod (or Deployment, StatefulSet, etc.) spec with the correct image.

2. **If the tag is supposed to exist in a private registry**  
   - Verify that the image is pushed to the registry.  
   - Ensure the node can reach the registry (network, DNS, firewall).  
   - Add an `imagePullSecret` to the pod spec and reference it with `imagePullSecrets`.

3. **Apply the corrected manifest** (example for a pod):

```bash
kubectl apply -f - <<'EOF'
apiVersion: v1
kind: Pod
metadata:
  name: s02-imagepull
  namespace: kubexplain-eval
spec:
  containers:
  - name: app
    image: nginx:latest   # <-- corrected tag
EOF
```

4. **Verify the pod starts**  

```bash
kubectl get pod s02-imagepull -n kubexplain-eval -w
```

   The pod should transition to **Running** once the image is successfully pulled.

### Quick checklist

- ✅ Image name and tag are correct and exist in the registry.  
- ✅ Node has network access to the registry (no firewall/DNS issues).  
- ✅ If private, `imagePullSecrets` are configured and contain valid credentials.  
- ✅ `imagePullPolicy` is appropriate (`IfNotPresent` or `Always` as needed).  

Fixing the image reference resolves the `ErrImagePull` / `ImagePullBackOff` condition and allows the pod to start.
