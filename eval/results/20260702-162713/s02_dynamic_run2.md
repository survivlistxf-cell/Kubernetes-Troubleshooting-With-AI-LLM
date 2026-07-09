# s02 | mode=dynamic | run=2 | 12.5s | needs_search=0

**Q:** My pod 's02-imagepull' in namespace 'kubexplain-eval' will not start. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** image cannot be pulled (bad name/tag or missing credentials); fix image reference

---

## Most likely reasons why **`s02‑imagepull`** can’t start (in order of probability)

| # | Reason | Why it’s common | How to verify | How to fix |
|---|--------|----------------|---------------|------------|
| 1 | **ImagePullBackOff / ErrImagePull** – the container image cannot be pulled | – Wrong image name or tag<br>– Image does not exist in the registry<br>– Registry is private and credentials are missing or expired | ```bash\nkubectl describe pod s02-imagepull -n kubexplain-eval | grep -A5 \"Events:\"```<br>Look for events such as `Failed to pull image`, `ImagePullBackOff`, `ErrImagePull`. The message will usually contain the exact error (e.g. `manifest for myrepo/app:latest not found`). | • Correct the image name/tag in the pod spec and re‑apply.<br>• If the registry is private, create or update a `Secret` of type `docker-registry` and reference it with `imagePullSecrets`.<br>• Ensure the secret is in the same namespace (`kubexplain-eval`). |
| 2 | **Missing or mis‑configured `imagePullSecrets`** | Private registries require a secret; a typo in the secret name or placing the secret in the wrong namespace will cause pull failures. | ```bash\nkubectl get pod s02-imagepull -n kubexplain-eval -o yaml | grep imagePullSecrets -A2```<br>Check that the secret name matches an existing secret (`kubectl get secret -n kubexplain-eval`). | • Create the secret (if it does not exist):<br>```bash\nkubectl create secret docker-registry my-reg-secret \\\n  --docker-server=REGISTRY_URL \\\n  --docker-username=USER \\\n  --docker-password=PASS \\\n  --docker-email=EMAIL \\\n  -n kubexplain-eval\n```<br>• Add the secret name to `imagePullSecrets` in the pod/deployment spec and re‑apply. |
| 3 | **Incorrect `imagePullPolicy`** | If the policy is set to `Never` or `IfNotPresent` and the image is not already cached on the node, the pull will be skipped and the container will fail. | ```bash\nkubectl get pod s02-imagepull -n kubexplain-eval -o yaml | grep imagePullPolicy```\n| • Set `imagePullPolicy: Always` (or remove the field to let the default apply) and re‑apply the manifest. |
| 4 | **Network connectivity problems** | Nodes cannot reach the registry (e.g., DNS failure, firewall, proxy mis‑configuration). | ```bash\nkubectl exec -it <any‑running‑pod> -n kubexplain-eval -- nslookup REGISTRY_HOST\nkubectl exec -it <any‑running‑pod> -n kubexplain-eval -- curl -v https://REGISTRY_HOST/v2/\n```<br>Or check node‑level logs (`journalctl -u kubelet`). | • Fix DNS / proxy settings on the nodes.<br>• Ensure outbound internet (or VPC‑to‑registry) traffic is allowed.<br>• If using a private registry behind a load balancer, verify the endpoint is reachable. |
| 5 | **Registry rate‑limiting or authentication lockout** | Public registries (Docker Hub, Quay, etc.) may throttle unauthenticated pulls after a certain number per hour. | Events will show messages like `pull access denied` or `rate limit exceeded`. | • Authenticate to the registry with a secret (see #2).<br>• Use a mirror or a private registry to cache images. |
| 6 | **Node resource pressure (disk, memory, CPU)** | If the node’s image cache directory (`/var/lib/containerd` or `/var/lib/docker`) is full, the kubelet cannot write the pulled layers. | ```bash\nkubectl describe node <node‑name> | grep -i \"disk\" -A3\n```<br>or check `df -h` on the node via SSH. | • Free up disk space on the node (delete unused images, clean up logs).<br>• Add more nodes or increase node size. |
| 7 | **Pod spec syntax error that silently drops the `image` field** | A misplaced indentation or misspelled key (`imagee:`) can cause the pod to be created without an image, leading to `CreateContainerConfigError`. | ```bash\nkubectl get pod s02-imagepull -n kubexplain-eval -o yaml | grep image```\nIf the field is missing, the spec is malformed. | • Validate the manifest locally (`kubectl apply --dry-run=client -f <file>`).<br>• Fix the YAML indentation or key name and re‑apply. |
| 8 | **Admission webhook rejecting the pod** | Some clusters have webhooks that enforce policies (e.g., only allow images from approved registries). The pod will stay in `Pending` with a `FailedAdmission` event. | ```bash\nkubectl describe pod s02-imagepull -n kubexplain-eval | grep -i \"admission\"```\n| • Adjust the image to meet the policy, or update the webhook configuration/exception. |
| 9 | **Pod is scheduled on a node that lacks the required runtime** | If the node runs a runtime that cannot handle the image format (e.g., trying to run a Windows image on a Linux node). | ```bash\nkubectl get pod s02-imagepull -n kubexplain-eval -o wide```\nCheck the node OS and runtime. | • Use node selectors or tolerations to schedule the pod onto a compatible node. |
|10| **Image is corrupted in the registry** | Pull succeeds but the runtime fails to unpack the layers, resulting in `ImageInspectError`. | Events will show `failed to unpack image`. | • Re‑push a fresh build of the image to the registry.<br>• Delete the cached copy on the node (`crictl rmi <image>` or `docker rmi`) and let it pull again. |

---

### General verification workflow (when you regain cluster access)

1. **Inspect the pod**  
   ```bash
   kubectl get pod s02-imagepull -n kubexplain-eval -o wide
   kubectl describe pod s02-imagepull -n kubexplain-eval
   ```

2. **Look at the events section** – this is where the kubelet records pull‑related errors.

3. **Check the pod’s YAML** for `image`, `imagePullPolicy`, and `imagePullSecrets`.  

4. **If the error points to a secret**, verify the secret exists and contains correct credentials:  
   ```bash
   kubectl get secret -n kubexplain-eval
   kubectl describe secret <secret-name> -n kubexplain-eval
   ```

5. **Test connectivity from inside the cluster** (run a temporary busybox pod) to the registry host.  

6. **If node‑level issues are suspected**, describe the node and, if possible, SSH to it to inspect disk space, runtime logs, and network routes.

---

### Quick “fix‑first” checklist

1. **Correct image name/tag** – most common simple mistake.  
2. **Add or fix `imagePullSecrets`** for private registries.  
3. **Set `imagePullPolicy: Always`** (or remove the field).  
4. **Validate YAML** (`kubectl apply --dry-run=client`).  
5. **Confirm node can reach the registry** (DNS, firewall, proxy).  
6. **Free node disk space** if the node is out of storage.  

Address the items in the order above; the first one that matches the observed event will most likely resolve the problem. Good luck!
