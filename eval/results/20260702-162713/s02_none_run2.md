# s02 | mode=none | run=2 | 13.5s | needs_search=0

**Q:** My pod 's02-imagepull' in namespace 'kubexplain-eval' will not start. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** image cannot be pulled (bad name/tag or missing credentials); fix image reference

---

## Most Likely Reasons the pod **`s02-imagepull`** (namespace **`kubexplain-eval`**) Won’t Start  

| # | Probable cause | Why it’s common | How to verify (when you have cluster access) | Typical fix |
|---|----------------|----------------|---------------------------------------------|-------------|
| 1 | **Image pull failure** (wrong image name, tag, or registry auth) | The pod name itself (`s02-imagepull`) hints that the problem is pulling the container image. | ```bash\nkubectl get pod s02-imagepull -n kubexplain-eval -o yaml | grep image\nkubectl describe pod s02-imagepull -n kubexplain-eval | grep -i \"pull\" -A5\nkubectl logs s02-imagepull -n kubexplain-eval --previous\n``` Look for `ErrImagePull`, `ImagePullBackOff`, or messages like *“repository not found”* or *“authentication required”*. | • Verify the image name/tag is correct.<br>• If the image is in a private registry, ensure a valid `imagePullSecret` exists and is referenced in the pod spec.<br>• If the registry uses TLS, confirm the cluster can reach it (no firewall / proxy issues).<br>• Update the pod (or Deployment/Job) with the correct image or secret and redeploy. |
| 2 | **Missing or mis‑configured `imagePullSecret`** | Even with a correct image, a private registry requires credentials. A typo in the secret name or missing secret will cause pull errors. | ```bash\nkubectl get pod s02-imagepull -n kubexplain-eval -o jsonpath='{.spec.imagePullSecrets[*].name}'\nkubectl describe secret <secret-name> -n kubexplain-eval\n``` | • Create or correct the secret: `kubectl create secret docker-registry <secret-name> --docker-server=... --docker-username=... --docker-password=... --docker-email=...`\n• Add the secret to the pod spec (or to the service account used by the pod). |
| 3 | **Network connectivity to the registry** | Pods need outbound network access to pull images. A network policy, firewall rule, or DNS issue can block the request. | ```bash\nkubectl exec -it <any‑running‑pod> -n kubexplain-eval -- nslookup <registry-host>\nkubectl exec -it <any‑running‑pod> -n kubexplain-eval -- curl -I https://<registry-host>/v2/\n``` | • Adjust NetworkPolicy to allow egress to the registry IP/port (usually 443).<br>• Verify DNS resolution for the registry host.<br>• Ensure any corporate proxy is correctly configured in the node’s Docker/container runtime. |
| 4 | **Node‑level image cache corruption** | If the node already attempted to pull the image and cached a corrupted layer, the pod may stay in `ImagePullBackOff`. | ```bash\nkubectl get pod s02-imagepull -n kubexplain-eval -o jsonpath='{.spec.nodeName}'\nkubectl describe node <node-name> | grep -i \"image\" -A3\n``` Then SSH to the node (or use a privileged pod) and run `crictl images` / `docker images` to see if the image exists and is marked `<none>` or corrupted. | • Delete the problematic image from the node: `crictl rmi <image-id>` or `docker rmi <image-id>`.<br>• The node will re‑pull the image cleanly on the next pod start. |
| 5 | **Pod spec errors unrelated to image** (e.g., invalid `command`, missing required ConfigMap/Secret, insufficient resources) | Even if the image pulls, the pod can stay in `Pending` or `CrashLoopBackOff` for other reasons. The name suggests an image issue, but it’s worth checking. | ```bash\nkubectl describe pod s02-imagepull -n kubexplain-eval | grep -i \"event\" -A10\nkubectl get pod s02-imagepull -n kubexplain-eval -o yaml\n``` Look for events like *“FailedMount”*, *“FailedCreatePodSandBox”*, *“Insufficient cpu”*, or *“CrashLoopBackOff”*. | • Fix missing ConfigMaps/Secrets, adjust resource requests/limits, correct command/args, or add required volume mounts.<br>• Apply the corrected manifest and redeploy. |
| 6 | **ServiceAccount lacks permission to use the secret** | If the pod’s ServiceAccount cannot read the `imagePullSecret`, the runtime will treat it as missing. | ```bash\nkubectl get serviceaccount <sa-name> -n kubexplain-eval -o yaml | grep imagePullSecrets\nkubectl auth can-i get secret <secret-name> --as=system:serviceaccount:kubexplain-eval:<sa-name>\n``` | • Bind the ServiceAccount to a Role/ClusterRole that permits `get` on the secret, or attach the secret directly to the pod spec. |
| 7 | **Registry rate‑limit or quota exceeded** | Some public registries (e.g., Docker Hub) throttle anonymous pulls; a burst can cause temporary failures. | ```bash\nkubectl logs s02-imagepull -n kubexplain-eval --previous | grep -i \"rate limit\"\n``` | • Use an authenticated pull (add a secret with your registry credentials).<br>• Pull from a mirror or increase quota if possible. |
| 8 | **Node is unschedulable or out of resources** (pod stuck in `Pending` before image pull) | If the scheduler cannot place the pod, it never reaches the image‑pull phase. | ```bash\nkubectl get pod s02-imagepull -n kubexplain-eval -o wide\nkubectl describe pod s02-imagepull -n kubexplain-eval | grep -i \"failed scheduling\"\n``` | • Free up CPU/memory on the node, add more nodes, or relax resource requests/limits.<br>• Remove any `nodeSelector`, `affinity`, or `taints` that prevent scheduling. |

---

### How to Prioritize Investigation  

1. **Check pod events first** – `kubectl describe pod …` will immediately show `ErrImagePull` or `ImagePullBackOff` if the issue is image‑related.  
2. **Validate the image reference and secret** – most pull problems are due to a typo or missing secret.  
3. **Confirm network reachability** – if the image name looks correct, a DNS or firewall block is the next common culprit.  
4. **Look at node‑level caches** – especially if the same image has been pulled successfully on other pods.  
5. **Inspect other spec errors** – only after ruling out pull problems should you dig into ConfigMaps, resources, etc.

---

### Quick “When You’re Back in the Cluster” Checklist  

```bash
# 1. Get a high‑level view
kubectl get pod s02-imagepull -n kubexplain-eval -o wide

# 2. Look at detailed events
kubectl describe pod s02-imagepull -n kubexplain-eval

# 3. Verify the image string
kubectl get pod s02-imagepull -n kubexplain-eval -o jsonpath='{.spec.containers[*].image}'

# 4. Check imagePullSecrets (if any)
kubectl get pod s02-imagepull -n kubexplain-eval -o jsonpath='{.spec.imagePullSecrets[*].name}'

# 5. Test registry connectivity from a node‑side pod
kubectl run tmp-check --rm -i --tty --image=busybox --restart=Never \
  -n kubexplain-eval -- nslookup <registry-host>

# 6. If secret is missing or wrong, recreate it
kubectl create secret docker-registry my-reg-secret \
  --docker-server=<registry> \
  --docker-username=<user> \
  --docker-password=<pass> \
  --docker-email=<email> -n kubexplain-eval

# 7. Patch the pod (or its controller) to reference the correct secret
kubectl patch pod s02-imagepull -n kubexplain-eval \
  --type='json' -p='[{"op":"add","path":"/spec/imagePullSecrets","value":[{"name":"my-reg-secret"}]}]'

# 8. If node cache is corrupted, delete the bad image on the node (run on the node)
#    crictl rmi <image-id>   or   docker rmi <image-id>
```

Follow the list in order; the first cause that matches the observed events is almost always the root problem. Fix it, then delete/recreate the pod (or let its controller roll a new replica) and the pod should start normally.
