CREATE TABLE mentor_profile_evidence (
    mentor_id UUID NOT NULL REFERENCES mentor_profile(user_id) ON DELETE CASCADE,
    display_order INTEGER NOT NULL,
    category VARCHAR(80) NOT NULL,
    claim VARCHAR(1000) NOT NULL,
    evidence_quote VARCHAR(1500) NOT NULL,
    source_page INTEGER,
    profile_version VARCHAR(100) NOT NULL,
    PRIMARY KEY (mentor_id, display_order),
    CONSTRAINT chk_mentor_profile_evidence_page CHECK (source_page IS NULL OR source_page > 0)
);

CREATE TABLE mentorship_request_recommendation_evidence (
    item_id UUID NOT NULL REFERENCES mentorship_request_recommendation_item(id) ON DELETE CASCADE,
    display_order INTEGER NOT NULL,
    category VARCHAR(80) NOT NULL,
    claim VARCHAR(1000) NOT NULL,
    evidence_quote VARCHAR(1500) NOT NULL,
    source_page INTEGER,
    profile_version VARCHAR(100) NOT NULL,
    PRIMARY KEY (item_id, display_order)
);

-- Demo evidence represents confirmed composite mentor profiles and is not claimed
-- to be extracted from real people or affiliations.
INSERT INTO mentor_profile_evidence
    (mentor_id, display_order, category, claim, evidence_quote, source_page, profile_version)
VALUES
    ('00000000-0000-0000-0000-000000000002', 0, 'USER_RESEARCH', 'Led MVP usability studies and converted findings into product decisions.', 'Planned and moderated usability tests for MVP releases; translated recurring user problems into prioritized product changes.', 1, 'demo-confirmed-v1'),
    ('00000000-0000-0000-0000-000000000002', 1, 'UX_STRATEGY', 'Built user-research practices for e-commerce and SaaS products.', 'Established interview, prototype-testing and research-synthesis practices across e-commerce and SaaS product teams.', 2, 'demo-confirmed-v1'),
    ('00000000-0000-0000-0000-000000000004', 0, 'HEALTHTECH', 'Built and validated B2B healthcare products.', 'Led customer discovery with hospitals and private clinics while developing a B2B HealthTech product.', 1, 'demo-confirmed-v1'),
    ('00000000-0000-0000-0000-000000000004', 1, 'PRODUCT_MARKET_FIT', 'Ran product-market-fit experiments for early-stage SaaS.', 'Designed pilot programs and pricing interviews to validate product-market fit before scaling sales.', 2, 'demo-confirmed-v1'),
    ('00000000-0000-0000-0000-000000000005', 0, 'PRODUCT_ANALYTICS', 'Used behavioral analytics to guide product prioritization.', 'Created product funnels and cohort analyses used to prioritize onboarding and retention improvements.', 1, 'demo-confirmed-v1'),
    ('00000000-0000-0000-0000-000000000005', 1, 'SEGMENTATION', 'Designed large-scale user segmentation and experiments.', 'Segmented players by lifecycle behavior and evaluated experiments across large consumer cohorts.', 2, 'demo-confirmed-v1'),
    ('00000000-0000-0000-0000-000000000006', 0, 'AI_MLOPS', 'Deployed and monitored machine-learning services.', 'Led deployment of Python machine-learning services with model monitoring, versioning and production observability.', 1, 'demo-confirmed-v1'),
    ('00000000-0000-0000-0000-000000000006', 1, 'DATA_ARCHITECTURE', 'Designed scalable data platforms for B2B products.', 'Designed event-driven data pipelines and analytics foundations for growing B2B SaaS platforms.', 2, 'demo-confirmed-v1'),
    ('00000000-0000-0000-0000-000000000007', 0, 'GO_TO_MARKET', 'Designed B2B go-to-market and sales experiments.', 'Built ideal-customer-profile, channel and sales-pipeline experiments for early-stage B2B companies.', 1, 'demo-confirmed-v1'),
    ('00000000-0000-0000-0000-000000000007', 1, 'PRICING', 'Improved SaaS pricing and revenue models.', 'Facilitated pricing research and packaging changes that improved expansion revenue for subscription products.', 2, 'demo-confirmed-v1'),
    ('00000000-0000-0000-0000-000000000008', 0, 'FUNDRAISING', 'Evaluated and prepared early-stage investment cases.', 'Reviewed pre-seed and seed investment cases across FinTech, HealthTech and enterprise software.', 1, 'demo-confirmed-v1'),
    ('00000000-0000-0000-0000-000000000008', 1, 'FINANCIAL_MODELING', 'Prepared financial models and investor narratives.', 'Supported founders in connecting operating assumptions, financial projections and investor presentations.', 2, 'demo-confirmed-v1'),
    ('00000000-0000-0000-0000-000000000009', 0, 'INTELLECTUAL_PROPERTY', 'Advised software and DeepTech teams on intellectual property.', 'Structured intellectual-property ownership and licensing approaches for software and DeepTech ventures.', 1, 'demo-confirmed-v1'),
    ('00000000-0000-0000-0000-000000000009', 1, 'DATA_PRIVACY', 'Prepared KVKK and technology-data agreements.', 'Reviewed KVKK obligations, data-processing terms and technology contracts for digital products.', 2, 'demo-confirmed-v1'),
    ('00000000-0000-0000-0000-000000000010', 0, 'GRANTS', 'Prepared TÜBİTAK and European funding applications.', 'Developed technical work packages, budgets and milestones for TÜBİTAK and European Union funding calls.', 1, 'demo-confirmed-v1'),
    ('00000000-0000-0000-0000-000000000010', 1, 'PROJECT_BUDGETING', 'Connected R&D plans with budgets and delivery schedules.', 'Converted technical development plans into auditable project budgets, deliverables and schedules.', 2, 'demo-confirmed-v1'),
    ('00000000-0000-0000-0000-000000000011', 0, 'INDUSTRIAL_IOT', 'Delivered industrial IoT pilots in production environments.', 'Managed industrial IoT pilots from field validation through production deployment with manufacturing customers.', 1, 'demo-confirmed-v1'),
    ('00000000-0000-0000-0000-000000000011', 1, 'SUPPLY_CHAIN', 'Improved manufacturing and supply-chain operations.', 'Led cross-functional improvements covering supplier readiness, pilot production and operational quality.', 2, 'demo-confirmed-v1'),
    ('00000000-0000-0000-0000-000000000012', 0, 'CLIMATETECH', 'Built sustainability-focused startup business models.', 'Developed ClimateTech business models connecting customer value, carbon impact and commercial viability.', 1, 'demo-confirmed-v1'),
    ('00000000-0000-0000-0000-000000000012', 1, 'IMPACT_INVESTMENT', 'Prepared carbon and impact metrics for investment.', 'Prepared carbon-accounting and impact-measurement frameworks used in sustainability investment discussions.', 2, 'demo-confirmed-v1');
