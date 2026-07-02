# s02 | mode=static | run=1 | 5.7s | needs_search=0

**Q:** My pod 's02-imagepull' in namespace 'kubexplain-eval' will not start. What is the root cause and how do I fix it?

**Expected:** image cannot be pulled (bad name/tag or missing credentials); fix image reference

---

## Root cause  

The pod is stuck in **`ImagePullBackOff`** because the container image reference does not exist:

```
nginx:tag-inexistent-xyz-123
```

The kubelet tried to pull the image from Docker Hub and received a **`NotFound`** error:

```
failed to resolve reference "docker.io/library/nginx:tag-inexistent-xyz-123": not found
```

Since the tag cannot be resolved, the kubelet cannot start the container, and it repeatedly backs‑off.

## How to fix it  

### 1. Use a valid image tag  

* Find an existing tag for the `nginx` image (e.g. `latest`, `1.25`, `alpine`, etc.).  
* Update the pod (or Deployment, StatefulSet, etc.) to reference the valid tag.

```bash
# Example: edit the pod manifest in‑place
kubectl edit pod s02-imagepull -n kubexplain-eval
```

Replace the line:

```yaml
image: nginx:tag-inexistent-xyz-123
```

with a valid one, e.g.:

```yaml
image: nginx:1.25
```

Then save the file; the pod will be recreated with the correct image.

### 2. If you need a custom image  

* Build the image locally or in a CI pipeline.  
* Push it to a registry you can reach (Docker Hub, a private registry, etc.).  
* Make sure the image name includes the correct registry host and tag, e.g.:

```bash
docker build -t myregistry.example.com/myteam/nginx:custom-v1 .
docker push myregistry.example.com/myteam/nginx:custom-v1
```

* Update the pod spec to use that full reference.

### 3. Verify the fix  

```bash
kubectl get pod s02-imagepull -n kubexplain-eval -w
```

The pod should transition from **`Pending`** → **`Running`** once the image is successfully pulled.

### 4. Optional: check registry connectivity (if you suspect network issues)

```bash
kubectl exec -it <any-pod-on-worker-1> -n kube-system -- curl -s https://registry-1.docker.io/v2/
```

A successful HTTP 200 response indicates the node can reach Docker Hub. If this fails, investigate network policies, firewall rules, or proxy settings.

---

**Summary:** The pod fails because the specified image tag does not exist in the registry. Correct the image reference (or push the intended image) and redeploy the pod. Once a valid image is available, the pod will start normally.
