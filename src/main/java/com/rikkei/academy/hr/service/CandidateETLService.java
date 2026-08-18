package com.rikkei.academy.hr.service;

import com.rikkei.academy.hr.dto.CandidateExtraction;
import com.rikkei.academy.hr.entity.Candidate;
import com.rikkei.academy.hr.exception.BusinessValidationException;
import com.rikkei.academy.hr.repository.CandidateRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.regex.Pattern;

@Service
public class CandidateETLService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^(\\+?[0-9]{9,15})$");

    private final ChatModel chatModel;
    private final CandidateRepository candidateRepository;
    private final BeanOutputConverter<CandidateExtraction> outputConverter;

    public CandidateETLService(ChatModel chatModel, CandidateRepository candidateRepository) {
        this.chatModel = chatModel;
        this.candidateRepository = candidateRepository;
        this.outputConverter = new BeanOutputConverter<>(CandidateExtraction.class);
    }

    public Candidate processResume(String resumeText) {
        if (!StringUtils.hasText(resumeText)) {
            throw new BusinessValidationException("Resume text must not be empty");
        }

        CandidateExtraction extraction = transformResumeWithAI(resumeText);
        validateCandidateData(extraction);
        return saveCandidateToDatabase(extraction);
    }

    private CandidateExtraction transformResumeWithAI(String resumeText) {
        String promptString = """
            === SYSTEM ROLE ===
            Ban la he thong AI trich xuat thong tin ung vien tu CV sang JSON.

            === INPUT RESUME TEXT ===
            {resumeText}

            === STRICT CONSTRAINTS ===
            1. Tra ve duy nhat ma JSON thuan tuy, khong co markdown code fence (```json), khong co cau chao hay giai thich.
            2. Chuan hoa so nam kinh nghiem thanh so nguyen.
            3. Danh sach ky nang (skills) trich xuat thanh mang cac tu khoa ky thuat.
            4. Trich xuat chinh xac so dien thoai va email.

            === FORMAT INSTRUCTIONS ===
            {formatInstructions}
            """;

        PromptTemplate promptTemplate = new PromptTemplate(promptString);
        Prompt prompt = promptTemplate.create(Map.of(
                "resumeText", resumeText,
                "formatInstructions", outputConverter.getFormatInstructions()
        ));

        String rawResponse = chatModel.call(prompt).getResult().getOutput().getText();
        return outputConverter.convert(rawResponse);
    }

    public void validateCandidateData(CandidateExtraction data) {
        if (data == null) {
            throw new BusinessValidationException("Candidate data is null");
        }

        if (!StringUtils.hasText(data.fullName()) || data.fullName().trim().length() < 2) {
            throw new BusinessValidationException("Invalid full name: " + data.fullName());
        }

        if (!StringUtils.hasText(data.email()) || !EMAIL_PATTERN.matcher(data.email().trim()).matches()) {
            throw new BusinessValidationException("Invalid email: " + data.email());
        }

        if (data.yearsExperience() == null || data.yearsExperience() < 0) {
            throw new BusinessValidationException("Years of experience must be non-negative: " + data.yearsExperience());
        }

        String cleanPhone = data.phone() != null ? data.phone().replaceAll("[\\s.-]", "") : "";
        if (!StringUtils.hasText(cleanPhone) || !PHONE_PATTERN.matcher(cleanPhone).matches()) {
            throw new BusinessValidationException("Invalid phone number: " + data.phone());
        }
    }

    @Transactional
    public Candidate saveCandidateToDatabase(CandidateExtraction dto) {
        String cleanPhone = dto.phone().replaceAll("[\\s.-]", "");
        Candidate candidate = new Candidate(
                dto.fullName().trim(),
                cleanPhone,
                dto.email().trim().toLowerCase(),
                dto.skills(),
                dto.yearsExperience()
        );
        return candidateRepository.save(candidate);
    }
}
