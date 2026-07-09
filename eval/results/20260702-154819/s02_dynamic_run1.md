# s02 | mode=dynamic | run=1 | 5.7s | needs_search=0

**Q:** My pod 's02-imagepull' in namespace 'kubexplain-eval' will not start. What is the root cause and how do I fix it?

**Expected:** image cannot be pulled (bad name/tag or missing credentials); fix image reference

---

## Root cause  

The pod is stuck in **`ErrImagePull` / `ImagePullBackOff`** because the container image it is trying to start does not exist:

```
nginx:tag-inexistent-xyz-123
```

The kubelet reports:

```
failed to resolve reference "docker.io/library/nginx:tag-inexistent-xyz-123": not found
```

So the registry (Docker Hub) cannot find a manifest for that tag. The pod never reaches the **Running** phase because the image cannot be pulled.

## How to fix it  

1. **Verify the image name and tag**  
   * Check Docker Hub (or your private registry) for the exact tag you want to use.  
   * If you intended to use the latest official Nginx image, the tag should be `latest` (or a valid version like `1.27`, `1.27-alpine`, etc.).

2. **Update the pod (or Deployment, StatefulSet, etc.)**  
   Replace the nonexistent tag with a valid one.

   ```yaml
   # Example pod spec snippet
   containers:
   - name: app
     image: nginx:latest          # <-- change to a real tag
   ```

   Apply the change:

   ```bash
   kubectl apply -f <your‑manifest>.yaml
   ```

   *If the pod is managed by a higher‑level controller (Deployment, ReplicaSet, etc.), edit that resource instead of the pod directly.*

3. **(Optional) Pull the image manually to confirm it exists**  

   ```bash
   docker pull nginx:latest
   ```

   If the pull succeeds, you know the tag is valid.

4. **Check image‑pull secrets (only if you are using a private registry)**  
   * If the image lives in a private registry, make sure a correctly configured `imagePullSecret` is attached to the service account.  
   * For Docker Hub public images, no secret is needed.

5. **Re‑create / restart the pod**  
   After fixing the image reference, delete the failing pod so the controller creates a fresh one:

   ```bash
   kubectl delete pod s02-imagepull -n kubexplain-eval
   ```

   The new pod should transition to **Running** once the image is pulled successfully.

## Summary  

- **Problem:** The specified image tag `nginx:tag-inexistent-xyz-123` does not exist in Docker Hub, causing `ErrImagePull` and `ImagePullBackOff`.  
- **Fix:** Use a valid image tag (e.g., `nginx:latest` or another existing tag), update the pod spec (or its owning controller), and redeploy the pod. If a private registry is involved, ensure proper pull secrets are configured. Once the image can be resolved, the pod will start normally.
