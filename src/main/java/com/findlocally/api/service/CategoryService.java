package com.findlocally.api.service;

import com.findlocally.api.repository.CategoryRepository;
import com.findlocally.api.model.Category;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CategoryService {

    private final CategoryRepository repository;

    public CategoryService(CategoryRepository repository) {
        this.repository = repository;
    }

    public List<Category> fetchCategories() {
        return repository.fetchCategories();
    }
}

