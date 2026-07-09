# s02 | mode=dynamic | run=3 | 6.3s | needs_search=0

**Q:** My pod 's02-imagepull' in namespace 'kubexplain-eval' will not start. What is the root cause and how do I fix it?

**Expected:** image cannot be pulled (bad name/tag or missing credentials); fix image reference

---

## Root cause  

The pod **s02-imagepull** is stuck in `ErrImagePull / ImagePullBackOff` because the container image it is trying to run does **not exist** in the registry:

```
nginx:tag-inexistent-xyz-123
```

The kubelet reports:

```
failed to resolve reference "docker.io/library/nginx:tag-inexistent-xyz-123": not found
```

Since Docker Hub (docker.io) cannot find that tag, the image cannot be pulled, so the pod never reaches the `Running` state.

## How to fix it  

1. **Verify the image name and tag**  
   * Check the official Nginx repository on Docker Hub (https://hub.docker.com/_/nginx) and pick a valid tag (e.g., `latest`, `1.27`, `1.27-alpine`, etc.).  
   * If you intended to use a custom image, make sure it has been pushed to the registry with the exact tag you reference.

2. **Update the pod (or Deployment, etc.)**  
   * Edit the manifest that creates the pod and replace the incorrect image reference with a valid one, for example:

   ```yaml
   containers:
   - name: app
     image: nginx:1.27-alpine   # <-- use a real tag
   ```

   * Apply the corrected manifest (`kubectl apply -f <file>`). If the pod is managed by a higher‑level controller (Deployment, ReplicaSet, etc.), update that controller instead; the new pods it creates will use the corrected image.

3. **If the image is private**  
   * Ensure the image exists in the private registry.  
   * Create a `Secret` of type `docker-registry` with the proper credentials and reference it in the pod’s `imagePullSecrets`.  
   * Verify network connectivity from the node to the registry (e.g., `curl https://my-registry.example.com/v2/`).

4. **Check `imagePullPolicy` (optional)**  
   * By default, `imagePullPolicy` is `IfNotPresent`. If you are testing a newly pushed tag with the same name as an older image, set `imagePullPolicy: Always` to force a fresh pull.

5. **Confirm the fix**  
   * After updating the manifest, watch the pod:

   ```
   kubectl get pod s02-imagepull -n kubexplain-eval -w
   ```

   * The pod should transition from `Pending` → `ContainerCreating` → `Running` once the image is successfully pulled.

### Quick checklist

- ✅ Image name and tag exist in the registry.  
- ✅ Registry is reachable from the node (no firewall / DNS issues).  
- ✅ If private, proper `imagePullSecrets` are configured.  
- ✅ Manifest is updated and applied.  

Once the correct image reference is in place, the pod will start normally.
