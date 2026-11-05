package com.malefashion.catalog.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VariantBatchRequest {

    @NotEmpty(message = "variantIds không được rỗng")
    private List<UUID> variantIds;
}
