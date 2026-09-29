package ru.sibvibe.approval.document.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.sibvibe.approval.document.entity.Document;
import ru.sibvibe.approval.document.service.DisplayStatus;
import ru.sibvibe.approval.document.service.DocumentListTab;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Проекции чтения документов. ACL входит в SQL до фильтров, count и pagination,
 * поэтому недоступные документы не влияют даже на total.
 *
 * Видимость «вся компания» действует только на отправленные документы: черновик видит лишь автор (и те, кто уже
 * согласовывал его прошлые версии, — ради истории), даже если автор заранее выбрал «видно всем». Одно и то же
 * условие в списке, карточке и скачивании файла — иначе черновик можно было бы открыть по прямому адресу.
 */
@Repository
public class DocumentReadRepository {

    private static final String READABLE = """
            d.org_id = :orgId
            and (
                d.author_id = :userId
                or (d.visibility = 'ORG' and d.status <> 'DRAFT')
                or exists (
                    select 1 from approval_step access_step
                    where access_step.document_id = d.id
                      and access_step.approver_id = :userId
                )
            )
            """;
    private static final String ACTIVE_STEP = """
            exists (
                select 1 from approval_step active_step
                where active_step.document_id = d.id
                  and d.status = 'IN_APPROVAL'
                  and active_step.version_no = d.current_version_no
                  and active_step.approver_id = :userId
                  and active_step.stage_order = d.current_stage
                  and active_step.decision = 'PENDING'
                  and active_step.activated_at is not null
            )
            """;

    /**
     * Показываемый статус — единственное место, где он вычисляется: список, фильтр, карточка и бот берут его
     * отсюда. «На утверждении» — документ на согласовании, и текущий этап — утверждение; «Утверждён» — согласован,
     * и в текущей версии утверждающий утвердил. Остальные — как в базе.
     */
    public static final String DISPLAY_STATUS = """
            case
                when d.status = 'IN_APPROVAL' and exists (
                    select 1 from approval_step endorse_step
                    where endorse_step.document_id = d.id
                      and endorse_step.version_no = d.current_version_no
                      and endorse_step.stage_order = d.current_stage
                      and endorse_step.kind = 'ENDORSEMENT'
                ) then 'IN_ENDORSEMENT'
                when d.status = 'APPROVED' and exists (
                    select 1 from approval_step endorse_step
                    where endorse_step.document_id = d.id
                      and endorse_step.version_no = d.current_version_no
                      and endorse_step.kind = 'ENDORSEMENT'
                      and endorse_step.decision = 'APPROVED'
                ) then 'ENDORSED'
                else d.status
            end""";

    private final JdbcClient jdbc;

    public DocumentReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Уходил ли документ хоть раз на согласование — у такого есть история решений. */
    public boolean everSubmitted(long documentId) {
        return Boolean.TRUE.equals(jdbc.sql("select exists (select 1 from approval_step where document_id = :documentId)")
                .param("documentId", documentId)
                .query(Boolean.class)
                .single());
    }

    public boolean canRead(long documentId, long orgId, long userId) {
        Boolean result = jdbc.sql("""
                        select exists (
                            select 1 from document d
                            where d.id = :documentId and %s
                        )
                        """.formatted(READABLE))
                .param("documentId", documentId)
                .param("orgId", orgId)
                .param("userId", userId)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(result);
    }

    /**
     * Показатели процесса по документам компании. Момент первой отправки берётся из
     * первого созданного шага: отдельного submitted_at в схеме нет, а все шаги версии
     * создаются атомарно при отправке. Повторная версия не начинает измерение заново.
     */
    public MetricsRow metrics(long orgId, Instant from, Instant toExclusive) {
        StringBuilder period = new StringBuilder();
        if (from != null) {
            period.append(" and submitted_at >= :from");
        }
        if (toExclusive != null) {
            period.append(" and submitted_at < :toExclusive");
        }

        String sql = """
                with submitted_documents as (
                    select d.id,
                           d.status,
                           d.updated_at,
                           min(step.created_at) as submitted_at,
                           bool_or(step.decision = 'RETURNED') as was_returned
                    from document d
                    join approval_step step on step.document_id = d.id
                    where d.org_id = :orgId
                    group by d.id, d.status, d.updated_at
                ), period_documents as (
                    select id,
                           status,
                           was_returned,
                           extract(epoch from (updated_at - submitted_at))::double precision / 3600.0
                               as approval_hours
                    from submitted_documents
                    where 1 = 1
                """ + period + """
                )
                select percentile_cont(0.5) within group (order by approval_hours)
                           filter (where status = 'APPROVED') as median_approval_hours,
                       avg(case when was_returned then 1.0 else 0.0 end) as return_rate,
                       count(*) as documents_count
                from period_documents
                """;

        JdbcClient.StatementSpec statement = jdbc.sql(sql).param("orgId", orgId);
        if (from != null) {
            statement = statement.param("from", from.atOffset(ZoneOffset.UTC));
        }
        if (toExclusive != null) {
            statement = statement.param("toExclusive", toExclusive.atOffset(ZoneOffset.UTC));
        }
        return statement.query((rs, rowNum) -> new MetricsRow(
                number(rs.getObject("median_approval_hours")),
                number(rs.getObject("return_rate")),
                rs.getLong("documents_count")))
                .single();
    }

    public PageRows findDocuments(
            DocumentListTab tab,
            long orgId,
            long userId,
            DisplayStatus status,
            Long typeId,
            Long authorId,
            Instant from,
            Instant toExclusive,
            String searchText,
            int page,
            int size
    ) {
        StringBuilder where = new StringBuilder(" where ").append(READABLE);
        String search = searchText == null || searchText.isBlank() ? null : searchText.strip();
        if (search != null) {
            // Название, автор, номер документа в приложении или регистрационный номер из самого документа.
            // fields бывает JSON null (не SQL NULL) — jsonb_array_elements на нём падал бы и ронял весь список.
            // Ещё — вид документа («записка» находит служебные записки, как бы они ни назывались), вид «Другого документа»,
            // как его назвал документ («акт»), и заголовок из текста. Плюс русская морфология PostgreSQL: «записки»,
            // «закупку» находят «записка», «закупка» (поиск находил только по названию).
            where.append("""
                     and (d.title ilike :search escape '\\'
                          or exists (select 1 from document_type search_type
                                     where search_type.id = d.document_type_id
                                       and (search_type.name ilike :search escape '\\'
                                            or to_tsvector('russian', search_type.name || ' ' || d.title)
                                               @@ plainto_tsquery('russian', :searchWords)))
                          or exists (select 1 from app_user search_author
                                     where search_author.id = d.author_id and search_author.full_name ilike :search escape '\\')
                          or cast(d.id as text) = :searchExact
                          or exists (select 1 from document_version search_version,
                                            jsonb_array_elements(case when jsonb_typeof(search_version.extracted_fields -> 'fields') = 'array'
                                       then search_version.extracted_fields -> 'fields' else '[]'::jsonb end) search_field
                                     where search_version.document_id = d.id
                                       and search_version.version_no = d.current_version_no
                                       and search_field ->> 'name' in ('reg_number', 'doc_kind', 'subject')
                                       and search_field ->> 'value' ilike :search escape '\\'))
                    """);
        }
        switch (tab) {
            case WAITING_ME -> where.append(" and ").append(ACTIVE_STEP);
            case MINE -> where.append(" and d.author_id = :userId");
            case AVAILABLE -> where.append(" and d.author_id <> :userId and not ").append(ACTIVE_STEP);
        }
        if (status != null) {
            where.append(" and (").append(DISPLAY_STATUS).append(") = :status");
        }
        if (typeId != null) {
            where.append(" and d.document_type_id = :typeId");
        }
        if (authorId != null) {
            where.append(" and d.author_id = :authorId");
        }
        if (from != null) {
            where.append(" and d.created_at >= :from");
        }
        if (toExclusive != null) {
            where.append(" and d.created_at < :toExclusive");
        }

        JdbcClient.StatementSpec count = bind(jdbc.sql(
                "select count(*) from document d" + where), orgId, userId,
                status, typeId, authorId, from, toExclusive, search);
        long total = count.query(Long.class).single();

        String sql = """
                select d.id, d.title, d.status, d.current_version_no, d.current_stage, d.updated_at,
                       """ + DISPLAY_STATUS + """
                        as display_status,
                       dt.id as type_id, dt.name as type_name,
                       author.id as author_id, author.full_name as author_name,
                       (
                           select min(waiting.activated_at)
                           from approval_step waiting
                           where waiting.document_id = d.id
                             and waiting.version_no = d.current_version_no
                             and waiting.approver_id = :userId
                             and waiting.stage_order = d.current_stage
                             and waiting.decision = 'PENDING'
                       ) as waiting_since,
                       (
                           -- до 80 символов: значение пришло от модели и в строке списка длинным быть не должно
                           select left(kind_field ->> 'value', 80)
                           from document_version kind_version,
                                jsonb_array_elements(case when jsonb_typeof(kind_version.extracted_fields -> 'fields') = 'array'
                                       then kind_version.extracted_fields -> 'fields' else '[]'::jsonb end) kind_field
                           where dt.code = 'GENERIC'
                             and kind_version.document_id = d.id
                             and kind_version.version_no = d.current_version_no
                             and kind_field ->> 'name' = 'doc_kind'
                             and coalesce(kind_field ->> 'value', '') <> ''
                           limit 1
                       ) as recognized_kind,
                       (
                           select kind_field ->> 'source'
                           from document_version kind_version,
                                jsonb_array_elements(case when jsonb_typeof(kind_version.extracted_fields -> 'fields') = 'array'
                                       then kind_version.extracted_fields -> 'fields' else '[]'::jsonb end) kind_field
                           where dt.code = 'GENERIC'
                             and kind_version.document_id = d.id
                             and kind_version.version_no = d.current_version_no
                             and kind_field ->> 'name' = 'doc_kind'
                           limit 1
                       ) as recognized_kind_source
                from document d
                join document_type dt on dt.id = d.document_type_id
                join app_user author on author.id = d.author_id
                """ + where + " order by d.updated_at desc, d.id desc limit :size offset :offset";
        JdbcClient.StatementSpec query = bind(jdbc.sql(sql), orgId, userId,
                status, typeId, authorId, from, toExclusive, search)
                .param("size", size)
                .param("offset", (long) page * size);
        List<ListRow> items = query.query((rs, rowNum) -> new ListRow(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getLong("type_id"),
                rs.getString("type_name"),
                Document.Status.valueOf(rs.getString("status")),
                DisplayStatus.valueOf(rs.getString("display_status")),
                rs.getLong("author_id"),
                rs.getString("author_name"),
                rs.getInt("current_version_no"),
                rs.getObject("current_stage", Integer.class),
                rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant(),
                Optional.ofNullable(rs.getObject("waiting_since", java.time.OffsetDateTime.class))
                        .map(java.time.OffsetDateTime::toInstant)
                        .orElse(null),
                rs.getString("recognized_kind"),
                "MODEL".equals(rs.getString("recognized_kind_source"))
        )).list();
        return new PageRows(items, total);
    }

    public Optional<FileAccess> findFile(long fileId) {
        return jdbc.sql("""
                        select f.id, f.version_id, f.storage_key, f.file_name, f.mime_type, f.file_size,
                               v.document_id, d.org_id
                        from document_file f
                        join document_version v on v.id = f.version_id
                        join document d on d.id = v.document_id
                        where f.id = :fileId
                        """)
                .param("fileId", fileId)
                .query((rs, rowNum) -> new FileAccess(
                        rs.getLong("id"),
                        rs.getLong("version_id"),
                        rs.getLong("document_id"),
                        rs.getLong("org_id"),
                        rs.getString("storage_key"),
                        rs.getString("file_name"),
                        rs.getString("mime_type"),
                        rs.getLong("file_size")))
                .optional();
    }

    public boolean canUserReadFile(long fileId, long userId) {
        Boolean result = jdbc.sql("""
                        select exists (
                            select 1
                            from document_file f
                            join document_version v on v.id = f.version_id
                            join document d on d.id = v.document_id
                            join organization_member member
                              on member.org_id = d.org_id
                             and member.user_id = :userId
                             and member.status = 'ACTIVE'
                            where f.id = :fileId
                              and (
                                  d.author_id = :userId
                                  or (d.visibility = 'ORG' and d.status <> 'DRAFT')
                                  or exists (
                                      select 1 from approval_step access_step
                                      where access_step.document_id = d.id
                                        and access_step.approver_id = :userId
                                  )
                              )
                        )
                        """)
                .param("fileId", fileId)
                .param("userId", userId)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(result);
    }

    /**
     * До {@code limit} документов компании, видимых пользователю (то же правило {@code READABLE}, что и у
     * списков и карточки), название которых содержит фрагмент без учёта регистра - для статуса по текстовому
     * запросу в боте (порт {@code document.DocumentLookup}). Свежие документы - первыми: так же,
     * как в {@link #findDocuments}, это самое вероятное совпадение при неточном названии.
     */
    public List<TitleMatch> findByTitleFragment(long orgId, long userId, String fragment, int limit) {
        return jdbc.sql("""
                        select d.id, d.title, d.status, d.current_stage, %s as display_status
                        from document d
                        where %s
                          and d.title ilike :fragment escape '\\'
                        order by d.updated_at desc, d.id desc
                        limit :limit
                        """.formatted(DISPLAY_STATUS, READABLE))
                .param("orgId", orgId)
                .param("userId", userId)
                .param("fragment", "%" + escapeLike(fragment) + "%")
                .param("limit", limit)
                .query((rs, rowNum) -> new TitleMatch(
                        rs.getLong("id"),
                        rs.getString("title"),
                        Document.Status.valueOf(rs.getString("status")),
                        DisplayStatus.valueOf(rs.getString("display_status")),
                        rs.getObject("current_stage", Integer.class)))
                .list();
    }

    /** Показываемый статус одного документа — для карточки; то же выражение, что у списков. */
    public DisplayStatus displayStatus(long documentId) {
        return DisplayStatus.valueOf(jdbc.sql("select " + DISPLAY_STATUS + " from document d where d.id = :documentId")
                .param("documentId", documentId)
                .query(String.class)
                .single());
    }

    /** {@code %}, {@code _} и {@code \} в пользовательском тексте - буквально, не как символы шаблона LIKE. */
    private static String escapeLike(String fragment) {
        return fragment.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public List<StepRow> findSteps(long documentId) {
        return jdbc.sql("""
                        select step.id, step.version_no, step.stage_order, step.origin, step.decision, step.kind,
                               step.comment, step.activated_at, step.decided_at, step.auto_reason,
                               role.id as role_id, role.code as role_code, role.name as role_name,
                               approver.id as approver_id, approver.full_name as approver_name
                        from approval_step step
                        join role on role.id = step.role_id
                        join app_user approver on approver.id = step.approver_id
                        where step.document_id = :documentId
                        order by step.version_no, step.stage_order, step.id
                        """)
                .param("documentId", documentId)
                .query((rs, rowNum) -> new StepRow(
                        rs.getLong("id"),
                        rs.getInt("version_no"),
                        rs.getInt("stage_order"),
                        rs.getString("origin"),
                        rs.getString("decision"),
                        rs.getString("kind"),
                        rs.getString("comment"),
                        instant(rs, "activated_at"),
                        instant(rs, "decided_at"),
                        rs.getString("auto_reason"),
                        rs.getLong("role_id"),
                        rs.getString("role_code"),
                        rs.getString("role_name"),
                        rs.getLong("approver_id"),
                        rs.getString("approver_name")))
                .list();
    }

    private JdbcClient.StatementSpec bind(
            JdbcClient.StatementSpec statement,
            long orgId,
            long userId,
            DisplayStatus status,
            Long typeId,
            Long authorId,
            Instant from,
            Instant toExclusive,
            String search
    ) {
        statement = statement.param("orgId", orgId).param("userId", userId);
        if (search != null) {
            statement = statement.param("search", "%" + escapeLike(search) + "%").param("searchExact", search)
                    .param("searchWords", search);
        }
        if (status != null) {
            statement = statement.param("status", status.name());
        }
        if (typeId != null) {
            statement = statement.param("typeId", typeId);
        }
        if (authorId != null) {
            statement = statement.param("authorId", authorId);
        }
        if (from != null) {
            statement = statement.param("from", from.atOffset(ZoneOffset.UTC));
        }
        if (toExclusive != null) {
            statement = statement.param("toExclusive", toExclusive.atOffset(ZoneOffset.UTC));
        }
        return statement;
    }

    private static Instant instant(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
        java.time.OffsetDateTime value = resultSet.getObject(column, java.time.OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Double number(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    public record PageRows(List<ListRow> items, long total) {}

    public record TitleMatch(long id, String title, Document.Status status, DisplayStatus displayStatus, Integer currentStage) {}

    public record MetricsRow(Double medianApprovalHours, Double returnRate, long documentsCount) {}

    public record ListRow(
            long id,
            String title,
            long typeId,
            String typeName,
            Document.Status status,
            DisplayStatus displayStatus,
            long authorId,
            String authorName,
            int currentVersionNo,
            Integer currentStage,
            Instant updatedAt,
            Instant waitingSince,
            /** Вид «Другого документа», как его назвал документ (поле doc_kind, D2/DOC-7); null у видов из шаблонов. */
            String recognizedKind,
            /** Вид нашёл ИИ, а не ввёл автор — рядом значок ✦. */
            boolean recognizedKindFromAi
    ) {}

    public record FileAccess(
            long fileId,
            long versionId,
            long documentId,
            long orgId,
            String storageKey,
            String fileName,
            String mimeType,
            long size
    ) {}

    public record StepRow(
            long id,
            int versionNo,
            int stageOrder,
            String origin,
            String decision,
            String kind,
            String comment,
            Instant activatedAt,
            Instant decidedAt,
            String autoReason,
            long roleId,
            String roleCode,
            String roleName,
            long approverId,
            String approverName
    ) {}
}
