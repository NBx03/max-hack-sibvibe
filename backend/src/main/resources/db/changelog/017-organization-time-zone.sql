--liquibase formatted sql
--changeset sibvibe:56-001-organization-time-zone dbms:postgresql

-- «сегодня» у компании — по её часовому поясу, а не по Москве. Правило «дата не позже
-- сегодняшнего дня», период в списках документов и показатели считают день по поясу компании: в Новосибирске после
-- полуночи уже новый день, и сегодняшняя дата не должна становиться «будущей». Город выбирают при создании компании,
-- администратор меняет его в разделе «Компания». Существующим компаниям — Москва, как было до сих пор.
ALTER TABLE organization ADD COLUMN city VARCHAR(64) NOT NULL DEFAULT 'Москва';
ALTER TABLE organization ADD COLUMN time_zone VARCHAR(64) NOT NULL DEFAULT 'Europe/Moscow';

-- Только часовые пояса России: продукт для российских компаний, а список на экране выбора — российские города.
ALTER TABLE organization ADD CONSTRAINT ck_organization_time_zone CHECK (time_zone IN (
    'Europe/Kaliningrad', 'Europe/Moscow', 'Europe/Samara', 'Asia/Yekaterinburg', 'Asia/Omsk', 'Asia/Novosibirsk',
    'Asia/Krasnoyarsk', 'Asia/Irkutsk', 'Asia/Yakutsk', 'Asia/Vladivostok', 'Asia/Magadan', 'Asia/Kamchatka'
));
