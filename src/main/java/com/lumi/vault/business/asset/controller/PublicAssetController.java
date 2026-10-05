package com.lumi.vault.business.asset.controller;

import com.lumi.vault.business.asset.dto.AssetUrlResponse;
import com.lumi.vault.business.asset.service.AssetService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
public class PublicAssetController {

    private final AssetService assetService;

    public PublicAssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    @GetMapping("/f/{id}")
    public ResponseEntity<Void> view(@PathVariable Long id) {
        return redirect(assetService.presign(id, false));
    }

    @GetMapping("/f/{id}/download")
    public ResponseEntity<Void> download(@PathVariable Long id) {
        return redirect(assetService.presign(id, true));
    }

    private ResponseEntity<Void> redirect(AssetUrlResponse target) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(target.url()))
                .build();
    }
}
