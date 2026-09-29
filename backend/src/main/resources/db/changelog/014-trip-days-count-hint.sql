--liquibase formatted sql
--changeset sibvibe:61-002-trip-days-count-hint dbms:postgresql

-- В заявках на командировку срок пишут «5 календарных дней», и модель копировала его целиком —
-- критичное T6 («целым числом») срабатывало на правильной заявке, и её нельзя было отправить. Подсказка просит
-- только само число, как в документе: «5» или «пять» (прописью T6 по-прежнему замечает — так и задумано).
UPDATE document_type_field field
SET hint = 'Только количество дней — само число, как в документе: цифрами («5») или словом («пять»), без слов «дней» и «календарных дней».'
FROM document_type type
WHERE type.id = field.document_type_id
  AND type.code = 'BUSINESS_TRIP_REQUEST'
  AND field.field_name = 'days_count';

-- Там же: модель брала адресата заявки за сотрудника, а дату составления — за дату начала. Тогда критичные T1 и T4
-- молчали на заявке без сотрудника и даты. Подсказки называют, что именно не брать.
UPDATE document_type_field field
SET hint = CASE field.field_name
    WHEN 'employee_name' THEN 'ФИО сотрудника, которого направляют в командировку, полностью, в именительном падеже. Не адресат заявки (кому она написана) и не подписавший её руководитель. Пусто, если сотрудник не назван.'
    ELSE 'Дата начала командировки точно в том виде, как она написана, без предлога «с». Не дата составления заявки. Пусто, если даты начала нет.'
END
FROM document_type type
WHERE type.id = field.document_type_id
  AND type.code = 'BUSINESS_TRIP_REQUEST'
  AND field.field_name IN ('employee_name', 'start_date');
