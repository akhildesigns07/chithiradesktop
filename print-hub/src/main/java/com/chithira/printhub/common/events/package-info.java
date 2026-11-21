/**
 * Domain events shared between modules. Planned: JobSent, JobStatusChanged, JobCounted
 * (published by the jobs module) so billing can make bill lines and inventory can
 * subtract paper stock without the jobs module knowing about them.
 */
package com.chithira.printhub.common.events;
