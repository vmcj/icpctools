package org.icpc.tools.contest.model;

/**
 * The current contest standings of a team.
 */
public interface IStanding {
	/**
	 * Return the number of problems that this team has solved.
	 *
	 * @return the number of problems solved
	 */
	int getNumSolved();

	/**
	 * Return the total score for this team.
	 *
	 * @return the total score
	 */
	double getScore();

	/**
	 * Return the total time this team has (sum of solution times + penalty), in ms.
	 *
	 * @return the total time of this team
	 */
	long getTime();

	/**
	 * Returns the time of the last (most recent) solution, in ms.
	 *
	 * @return the time of last solution
	 */
	long getLastSolutionTime();

	/**
	 * Return the current rank of this team.
	 *
	 * @return the rank
	 */
	String getRank();
}