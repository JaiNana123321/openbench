import type { Credits, DayRange, Instant, LocalDate, TimeWindow } from '../api/types';

// Display helpers. They format what the server computed; they never compute a Quote or a Balance.
// Times always show in the company time zone, whatever the browser's zone is.

export const COMPANY_TIME_ZONE = 'America/New_York';

const creditsFormat = new Intl.NumberFormat('en-US', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

/** Credits to two decimals: 1120 gives "1,120.00". */
export function formatCredits(credits: Credits): string {
  return creditsFormat.format(credits);
}

/** An upper-case value from the API in words: "RESOURCE_OWNER" gives "Resource owner". */
export function formatEnum(value: string): string {
  const words = value.toLowerCase().replaceAll('_', ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}

const dayAndTime = new Intl.DateTimeFormat('en-US', {
  timeZone: COMPANY_TIME_ZONE,
  weekday: 'short',
  month: 'short',
  day: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

function dayAndTimeOf(instant: Instant): { day: string; time: string } {
  const part = partsOf(dayAndTime, new Date(instant));
  return { day: `${part.weekday} ${part.month} ${part.day}`, time: `${part.hour}:${part.minute}` };
}

/** An instant in the company time zone: "Tue Oct 27, 14:00". */
export function formatInstant(instant: Instant): string {
  const { day, time } = dayAndTimeOf(instant);
  return `${day}, ${time}`;
}

/**
 * A time window in the company time zone: "Tue Oct 27, 14:00 to 16:00", or
 * "Tue Oct 27, 09:00 to Thu Oct 29, 09:00" when it ends on another day. The end is exclusive.
 */
export function formatWindow(timeWindow: TimeWindow): string {
  const start = dayAndTimeOf(timeWindow.start);
  const end = dayAndTimeOf(timeWindow.end);
  const endText = end.day === start.day ? end.time : `${end.day}, ${end.time}`;
  return `${start.day}, ${start.time} to ${endText}`;
}

const numericParts = new Intl.DateTimeFormat('en-US', {
  timeZone: COMPANY_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23',
});

/**
 * The value of an `<input type="datetime-local">`, such as "2026-10-27T14:00", read as a time in
 * the company time zone, as the ISO-8601 instant the API takes: "2026-10-27T14:00:00-04:00".
 */
export function toInstant(pickerValue: string): Instant {
  const [date, time] = pickerValue.split('T');
  const [year, month, day] = date.split('-').map(Number);
  const [hour, minute] = time.split(':').map(Number);
  const asIfUtc = Date.UTC(year, month - 1, day, hour, minute);
  // The zone's offset depends on the instant (daylight saving time), so find it in two steps.
  const offset = offsetMinutes(asIfUtc - offsetMinutes(asIfUtc) * 60_000);
  return `${date}T${pad(hour)}:${pad(minute)}:00${formatOffset(offset)}`;
}

/** The inverse of toInstant: an instant as a date-time picker value in the company time zone. */
export function toPickerValue(instant: Instant): string {
  const part = partsOf(numericParts, new Date(instant));
  return `${part.year}-${part.month}-${part.day}T${part.hour}:${part.minute}`;
}

/** The company zone's offset from UTC at an instant, in minutes: -240 in summer, -300 in winter. */
function offsetMinutes(utcMillis: number): number {
  const part = partsOf(numericParts, new Date(utcMillis));
  const wallClock = Date.UTC(
    Number(part.year),
    Number(part.month) - 1,
    Number(part.day),
    Number(part.hour),
    Number(part.minute),
    Number(part.second),
  );
  return Math.round((wallClock - utcMillis) / 60_000);
}

function formatOffset(minutes: number): string {
  const sign = minutes < 0 ? '-' : '+';
  const absolute = Math.abs(minutes);
  return `${sign}${pad(Math.floor(absolute / 60))}:${pad(absolute % 60)}`;
}

function pad(value: number): string {
  return String(value).padStart(2, '0');
}

const dayFormat = new Intl.DateTimeFormat('en-US', {
  timeZone: 'UTC',
  month: 'short',
  day: 'numeric',
  year: 'numeric',
});

/** A day as the API sends it, "2026-10-01", shown as "Oct 1, 2026". A day has no time zone. */
export function formatDay(day: LocalDate): string {
  return dayFormat.format(new Date(`${day}T00:00:00Z`));
}

/** An inclusive range of days: "Oct 1, 2026 to Oct 31, 2026". */
export function formatDayRange(range: DayRange): string {
  return `${formatDay(range.from)} to ${formatDay(range.to)}`;
}

/** Today in the company time zone, as the API writes days: "2026-10-23". For default selections only. */
export function todayInCompanyZone(): LocalDate {
  const part = partsOf(numericParts, new Date());
  return `${part.year}-${part.month}-${part.day}`;
}

function partsOf(format: Intl.DateTimeFormat, date: Date): Record<string, string> {
  return Object.fromEntries(format.formatToParts(date).map((part) => [part.type, part.value]));
}
