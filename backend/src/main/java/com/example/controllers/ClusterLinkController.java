package com.example.controllers;

import com.example.entities.ClusterLink;
import com.example.repositories.ClusterLinkRepository;
import com.example.services.ClusterLinkTestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/cluster-links")
public class ClusterLinkController {

    private final ClusterLinkRepository linkRepo;
    private final ClusterLinkTestService linkTester;

    public ClusterLinkController(ClusterLinkRepository linkRepo,
                                 ClusterLinkTestService linkTester) {
        this.linkRepo = linkRepo;
        this.linkTester = linkTester;
    }

    @GetMapping
    public List<ClusterLink> list() {
        return linkRepo.findAllByOrderByIdAsc();
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<?> test(@PathVariable Long id) {
        Optional<ClusterLink> opt = linkRepo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(linkTester.testLink(opt.get()));
    }
}
