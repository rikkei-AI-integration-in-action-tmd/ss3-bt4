# BAI 4: SANG TAO - MODULE ETL RESUME PARSER (RIKKEI ACADEMY HR)

## 1. SO DO ASCII LUONG DU LIEU ETL

[ UNSTRUCTURED CV ] (Van ban CV tho)
        |
        v
+-----------------------+
|   1. EXTRACT STAGE    |  --> Nhan String resumeText tho
+-----------------------+      Khong mo Transaction / Khong muon DB Connection
        |
        v
+-----------------------+
|  2. TRANSFORM STAGE   |  --> Goi ChatModel + BeanOutputConverter<CandidateExtraction>
|     (LLM Parsing)     |  --> Xu ly trich xuat JSON, thoi gian mat 2s - 10s
+-----------------------+      Nam NGOAI @Transactional
        |
        v [ Java Record: CandidateExtraction ]
+-----------------------+
|  3. VALIDATE STAGE    |  --> Kiem tra tinh hop le du lieu:
|  (In-Memory Checks)   |      - fullName khong rong, do dai >= 2
+-----------------------+      - email dung dinh dang Regex RFC 5322
        |                      - yearsExperience >= 0
        |                      - phone hop le (9-15 chu so)
        v (Hop le)
+-----------------------+
|     4. LOAD STAGE     |  --> Map DTO Record sang JPA Entity Candidate
| (Database Persistence)|  --> Mo @Transactional ngan han trong saveCandidateToDatabase:
+-----------------------+      Muon DB Connection -> INSERT -> Commit -> Tra Connection (2ms - 5ms)
        |
        v
+-----------------------+
|   SQL DATABASE (DB)   |  --> Luu vao bang candidates thanh cong
+-----------------------+

---

## 2. PHAN TICH CHUYEN SAU TRADE-OFF VE @TRANSACTIONAL KHI GOI API LLM

### 2.1. Truong hop dat ChatModel call BÊN TRONG @Transactional
- Nhuoc diem (Can kiet Connection Pool - HikariCP Starvation):
  Khi phuong thuc duoc danh dau @Transactional, Spring/Hibernate se muon 1 Connection tu Database Connection Pool ngay tu dau.
  Lenh goi mang toi LLM thuong mat tu 3 - 20 giay. Trong suot khoang thoi gian nay, DB Connection bi giam long (idle) ma khong thuc thi bat ky cau lenh SQL nao.
  Neu co 10 yeu cau upload CV dong thoi (voi default maximum-pool-size = 10 cua HikariCP), toan bo connection se bi chiem dung. Cac request khac (ke ca xem danh sach, login) deu bi treo va gay loi ConnectionTimeoutException tren toan he thong (Cascading Failure).
- Uu diem: Rollback tu dong neu co thao tac DB truoc do (tuy nhien buoc LLM o dau quy trinh nen khong mang lai loi ich).

### 2.2. Truong hop dat ChatModel call BÊN NGOÀI @Transactional (Khuyen nghi)
- Uu diem:
  Tach biet 3 giai doan:
  1. Extract & Transform (I/O mang voi LLM): Chay hoan toan ngoai transaction, khong ton DB connection.
  2. Validate: Kiem tra tren bo nho RAM.
  3. Load (Luu DB): Chi mo @Transactional o ham saveCandidateToDatabase. DB Connection chi bi chiem dung trong 2 - 5ms khi INSERT roi tra lai pool ngay lap tuc.
  He thong dat throughput toi da va chiu tai cao, tranh duoc nguy co can kiet connection pool.
- Nhuoc diem: Can to chuc code thanh cac phuong thuc tach biet de phan dinh transaction boundary ro rang.

---

## 3. MA NGUON JAVA HOAN CHINH

### 3.1. Record DTO (CandidateExtraction.java)
package com.rikkei.academy.hr.dto;

import java.util.List;

public record CandidateExtraction(
    String fullName,
    String phone,
    String email,
    List<String> skills,
    Integer yearsExperience
) {}

### 3.2. JPA Entity (Candidate.java)
package com.rikkei.academy.hr.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "candidates")
public class Candidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "email", nullable = false, length = 150, unique = true)
    private String email;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "candidate_skills", joinColumns = @JoinColumn(name = "candidate_id"))
    @Column(name = "skill_name")
    private List<String> skills = new ArrayList<>();

    @Column(name = "years_experience", nullable = false)
    private Integer yearsExperience;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Candidate() {}

    public Candidate(String fullName, String phone, String email, List<String> skills, Integer yearsExperience) {
        this.fullName = fullName;
        this.phone = phone;
        this.email = email;
        this.skills = skills != null ? skills : new ArrayList<>();
        this.yearsExperience = yearsExperience;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public List<String> getSkills() { return skills; }
    public void setSkills(List<String> skills) { this.skills = skills; }
    public Integer getYearsExperience() { return yearsExperience; }
    public void setYearsExperience(Integer yearsExperience) { this.yearsExperience = yearsExperience; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

### 3.3. JPA Repository (CandidateRepository.java)
package com.rikkei.academy.hr.repository;

import com.rikkei.academy.hr.entity.Candidate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CandidateRepository extends JpaRepository<Candidate, Long> {
    Optional<Candidate> findByEmail(String email);
    boolean existsByEmail(String email);
}

### 3.4. Service (CandidateETLService.java)
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

### 3.5. Exception (BusinessValidationException.java)
package com.rikkei.academy.hr.exception;

public class BusinessValidationException extends RuntimeException {
    public BusinessValidationException(String message) {
        super(message);
    }
}

---

## 4. MINH CHUNG CHAY THUC TE (TEXT LOG)

### 4.1. Van ban CV tho dau vao
Ho va ten: Tran Duc Bo
Email: bo.tranduc@rikkeiacademy.edu.vn | Hotline: 0912345678
Tom tat kinh nghiem:
- Hon 3 nam kinh nghiem phat trien ung dung backend voi he sinh thai Java.
- Ky nang: Java Core, Spring Boot, Spring Data JPA, Microservices, PostgreSQL, Docker, Redis.
- Xay dung he thong quan ly nhan su quy mo 500+ nguoi.

### 4.2. Prompt gui di
=== SYSTEM ROLE ===
Ban la he thong AI trich xuat thong tin ung vien tu CV sang JSON.

=== INPUT RESUME TEXT ===
Ho va ten: Tran Duc Bo
Email: bo.tranduc@rikkeiacademy.edu.vn | Hotline: 0912345678
Tom tat kinh nghiem:
- Hon 3 nam kinh nghiem phat trien ung dung backend voi he sinh thai Java.
- Ky nang: Java Core, Spring Boot, Spring Data JPA, Microservices, PostgreSQL, Docker, Redis.
- Xay dung he thong quan ly nhan su quy mo 500+ nguoi.

=== STRICT CONSTRAINTS ===
1. Tra ve duy nhat ma JSON thuan tuy, khong co markdown code fence (```json), khong co cau chao hay giai thich.
2. Chuan hoa so nam kinh nghiem thanh so nguyen.
3. Danh sach ky nang (skills) trich xuat thanh mang cac tu khoa ky thuat.
4. Trich xuat chinh xac so dien thoai va email.

=== FORMAT INSTRUCTIONS ===
Your response should be in JSON format.
Do not include any explanations, only provide a RFC8259 compliant JSON response following this format without other spaces or markdown:
{"fullName":"string","phone":"string","email":"string","skills":["string"],"yearsExperience":0}

### 4.3. Ket qua JSON tra ve tu LLM
{
  "fullName": "Tran Duc Bo",
  "phone": "0912345678",
  "email": "bo.tranduc@rikkeiacademy.edu.vn",
  "skills": ["Java Core", "Spring Boot", "Spring Data JPA", "Microservices", "PostgreSQL", "Docker", "Redis"],
  "yearsExperience": 3
}

### 4.4. Ket qua thuc thi luong ETL va luu Database
1. Extract: Nhan text CV tho (268 ky tu)
2. Transform: ChatModel parse thanh cong sang CandidateExtraction record
3. Validate:
   - fullName: "Tran Duc Bo" [PASS]
   - email: "bo.tranduc@rikkeiacademy.edu.vn" [PASS]
   - yearsExperience: 3 [PASS]
   - phone: "0912345678" [PASS]
4. Load: Luu Candidate vao MySQL qua JPA thanh cong, ID: 101.
