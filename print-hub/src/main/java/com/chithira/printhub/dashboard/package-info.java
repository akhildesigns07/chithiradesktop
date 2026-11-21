/**
 * Dashboards: overall, prints, papers, waste and customers. One query loads the jobs of the chosen date range
 * ({@link DashboardRepository}); {@link DashboardService} works out every total, daily series and grouping in Java,
 * so the numbers are the same on PostgreSQL and H2. Printed and waste figures count only jobs whose counts were
 * entered (status COUNTED); "sheets sent" counts every job that reached the Fiery or the printer.
 */
package com.chithira.printhub.dashboard;
