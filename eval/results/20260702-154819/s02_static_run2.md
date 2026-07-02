# s02 | mode=static | run=2 | 7.3s | needs_search=0

**Q:** My pod 's02-imagepull' in namespace 'kubexplain-eval' will not start. What is the root cause and how do I fix it?

**Expected:** image cannot be pulled (bad name/tag or missing credentials); fix image reference

---

### What the pod description tells us

| Field | Value | Meaning |
|-------|-------|---------|
| **Image** | `nginx:tag-inexistent-xyz-123` | The pod is trying to pull a tag that does **not** exist in Docker Hub. |
| **Events** | `Failed to pull image … not found` → `ErrImagePull` → `ImagePullBackOff` | The kubelet asked the container runtime to download the image, the registry answered *404 Not Found*, and the kubelet is now backing‑off from further attempts. |
| **Status** | `Pending` / `ImagePullBackOff` | The container never started because the image could not be retrieved. |

**Root cause:** the image reference `nginx:tag-inexistent-xyz-123` does not exist in the public Docker Hub registry (or any other registry the cluster can reach). The kubelet therefore cannot pull the image, leading to the `ImagePullBackOff` condition.

---

### How to fix it

1. **Verify the image tag**  
   - From a workstation (or directly on a node) run:  
     ```bash
     docker pull nginx:tag-inexistent-xyz-123
     ```  
   - You will see the same *not found* error, confirming the tag is invalid.

2. **Choose a valid image**  
   - Use an existing tag, e.g. `nginx:latest` or a specific released version such as `nginx:1.27.0`.  
   - If you need a custom image, push it to a registry first and make sure the tag you reference matches exactly.

3. **Update the pod (or the higher‑level controller)**  
   - If the pod is created directly (e.g. via `kubectl run` or a plain `Pod` manifest), edit the manifest and replace the image line:  
     ```yaml
     spec:
       containers:
       - name: app
         image: nginx:latest   # <-- change to a valid tag
     ```  
   - If the pod is managed by a Deployment, DaemonSet, etc., update the controller so that new pods are created with the correct image:  
     ```bash
     kubectl set image deployment/<name> app=nginx:latest -n kubexplain-eval
     # or edit the manifest:
     kubectl edit deployment <name> -n kubexplain-eval
     ```

4. **Apply the change**  
   - After correcting the image, the controller will create a new pod (or the edited pod will be re‑evaluated).  
   - The kubelet will pull the image successfully and the pod will transition to `Running`.

5. **Optional: verify the fix**  
   ```bash
   kubectl get pod s02-imagepull -n kubexplain-eval -w
   ```  
   You should see the pod move from `Pending` → `ContainerCreating` → `Running` without any `ImagePullBackOff` events.

---

### Quick checklist

- [ ] **Image name & tag exist** in the registry you are using.  
- [ ] **Network connectivity** from the node to the registry (not an issue here; the error is *NotFound*, not a timeout).  
- [ ] **ImagePullPolicy** is appropriate (default `IfNotPresent` is fine for public images).  
- [ ] **If using a private registry**, ensure a valid `imagePullSecret` is attached (not needed for Docker Hub public images).  

Once the pod references a real, reachable image, the `ImagePullBackOff` condition will disappear and the pod will start normally.
