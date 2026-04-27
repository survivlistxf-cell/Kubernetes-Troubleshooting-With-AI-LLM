package com.example.services;

import com.example.entities.ClusterConfig;
import com.example.repositories.ClusterConfigRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Auto-detects the existing kubeconfig file at startup and creates
 * a default cluster entry in the database if none exists yet.
 * 
 * This ensures backward compatibility — the existing OpenStack cluster
 * (licenta-cluster.yaml) is automatically available without manual config.
 */
@Service
public class ClusterSeedService {

    private final ClusterConfigRepository clusterRepo;
    private final KubectlService kubectlService;

    public ClusterSeedService(ClusterConfigRepository clusterRepo, KubectlService kubectlService) {
        this.clusterRepo = clusterRepo;
        this.kubectlService = kubectlService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedDefaultCluster() {
        // Only seed if there are no clusters at all
        if (clusterRepo.count() > 0) {
            System.out.println("[ClusterSeed] Clusters already exist in DB, skipping auto-seed.");
            return;
        }

        String kubeconfigPath = kubectlService.resolveKubeconfigPath();
        Path path = Paths.get(kubeconfigPath);

        if (!Files.exists(path)) {
            System.out.println("[ClusterSeed] No kubeconfig found at " + kubeconfigPath + ", skipping auto-seed.");
            return;
        }

        System.out.println("[ClusterSeed] Auto-seeding default cluster from: " + kubeconfigPath);

        ClusterConfig defaultCluster = new ClusterConfig();
        defaultCluster.setName("default-cluster");
        defaultCluster.setDisplayName("Default Cluster (Auto-detected)");
        defaultCluster.setKubeconfigPath(kubeconfigPath);
        defaultCluster.setDefaultNamespace("elearning");
        defaultCluster.setDefault(true);
        defaultCluster.setActive(true);

        clusterRepo.save(defaultCluster);
        System.out.println("[ClusterSeed] Default cluster seeded successfully with namespace 'elearning'.");
    }
}
